package com.aisolutions.hrmanagement.service.leave;

import com.aisolutions.hrmanagement.dto.DropdownOptionDTO;
import com.aisolutions.hrmanagement.dto.LeaveApplicationDTO;
import com.aisolutions.hrmanagement.dto.LeaveBalanceDTO;
import com.aisolutions.hrmanagement.dto.StaffProfileDTO;
import com.aisolutions.hrmanagement.entity.LeaveApplication;
import com.aisolutions.hrmanagement.entity.LeaveTypeEntitlement;
import com.aisolutions.hrmanagement.entity.Staff;
import com.aisolutions.hrmanagement.repository.LeaveApplicationRepository;
import com.aisolutions.hrmanagement.repository.LeaveTypeRepository;
import com.aisolutions.hrmanagement.repository.NotificationRepository;
import com.aisolutions.hrmanagement.repository.StaffRepository;
import com.aisolutions.hrmanagement.service.CurrentUserService;
import com.aisolutions.hrmanagement.service.useractionlog.UserActionLogService;
import com.aisolutions.hrmanagement.service.useractionlog.UserActionLogService.DeviceInfo;

import com.aisolutions.shared.util.DateUtil;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Staff-side leave applications (m18LeaveApplications), driving the 4-step wizard:
 *   Step 1 particulars (name/dept prefilled + locked) → Step 2 details (apply/cancel + type
 *   + remarks) → Step 3 period (dates + AM/PM + working days) → Step 4 approver → submit.
 *
 * Balance policy (Sabrina, 2026-08-20): entitlement is computed from years of service against
 * the m01LeaveTypeEntitlement bands, per CALENDAR YEAR; the check WARNS but never blocks, and
 * a staff member with no join date on record is warned "balance not verified" rather than blocked.
 */
@ApplicationScoped
public class LeaveService {

    // "I wish to" (Step 2)
    public static final String ACTION_APPLY  = "APPLY";
    public static final String ACTION_CANCEL = "CANCEL";

    // Status lifecycle — a new application starts PENDING (awaiting the manager's decision)
    public static final String STATUS_PENDING   = "PENDING";
    public static final String STATUS_APPROVED  = "APPROVED";
    public static final String STATUS_REJECTED  = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    // Half-day
    private static final String HALF_AM = "AM";
    private static final String HALF_PM = "PM";
    private static final BigDecimal HALF = new BigDecimal("0.5");

    // Notification / audit wiring.
    private static final String MODULE_ID = "mod18";
    /** Approver-facing type ("a leave awaits your decision") — shown in the admin/shell bell,
     *  mirroring Admin-Claims. Staff-facing leave outcomes use "Staff-Leaves" (written on the
     *  admin side when a decision is made). */
    private static final String NOTIF_TYPE_ADMIN = "Admin-Leaves";
    private static final int LEN_NOTIF_SUBJECT = 200;
    private static final int LEN_NOTIF_DESC    = 255;
    private static final int LEN_LOG_REFERENCE = 45;
    private static final int LEN_LOG_REMARKS   = 255;

    @Inject LeaveApplicationRepository leaveRepo;
    @Inject LeaveTypeRepository leaveTypeRepo;
    @Inject StaffRepository staffRepo;
    @Inject CurrentUserService currentUserService;
    @Inject UserActionLogService userActionLogService;
    @Inject NotificationRepository notificationRepo;

    // ─────────────────────────────────────────────────────────
    //  WIZARD LOOKUPS (Step 1, 2, 4)
    // ─────────────────────────────────────────────────────────

    /** Step 1 prefill: the current user's name + department (locked fields). */
    public Uni<StaffProfileDTO> getProfile(String requestedStaffId) {
        return resolveStaffId(requestedStaffId).flatMap(staffId ->
            staffRepo.findByStaffId(staffId).map(s -> {
                if (s == null) {
                    return new StaffProfileDTO(staffId, staffId, null);
                }
                String name = (s.getName() != null && !s.getName().isBlank()) ? s.getName() : staffId;
                return new StaffProfileDTO(staffId, name, s.getDepartment());
            }));
    }

    /** Step 2: leave-type dropdown (value = code, label = description). */
    public Uni<List<DropdownOptionDTO>> getLeaveTypeOptions() {
        return leaveTypeRepo.findAllOptions();
    }

    /** Step 4: approver dropdown (all staff). */
    public Uni<List<DropdownOptionDTO>> getApproverOptions() {
        return staffRepo.findApproverOptions();
    }

    // ─────────────────────────────────────────────────────────
    //  BALANCE (Step 3 warning)
    // ─────────────────────────────────────────────────────────

    /**
     * The current user's balance for a leave type in the current calendar year.
     * entitledDays = the highest band whose YearOfService ≤ the staff's completed years of
     * service; takenDays = APPLY leaves (PENDING or APPROVED) whose FromDate falls in this
     * year. When the staff has no join date, entitlement is unknown and the UI only warns.
     */
    public Uni<LeaveBalanceDTO> getBalance(String requestedStaffId, String leaveType) {
        if (leaveType == null || leaveType.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("leaveType is required"));
        }
        return resolveStaffId(requestedStaffId).flatMap(staffId ->
            staffRepo.findByStaffId(staffId).flatMap(staff ->
                leaveTypeRepo.findDescription(leaveType).flatMap(desc ->
                    leaveTypeRepo.findEntitlements(leaveType).flatMap(bands -> {
                        int year = DateUtil.nowSGT().getYear();
                        LocalDate start = LocalDate.of(year, 1, 1);
                        LocalDate end   = LocalDate.of(year, 12, 31);
                        return leaveRepo.sumBookedDays(staffId, leaveType, start, end).map(taken -> {
                            LeaveBalanceDTO dto = new LeaveBalanceDTO();
                            dto.setLeaveType(leaveType);
                            dto.setLeaveTypeDescription(desc);
                            dto.setYear(year);
                            dto.setTakenDays(taken);

                            Integer serviceYears = serviceYears(staff);
                            if (serviceYears == null) {
                                dto.setEntitlementKnown(false);
                                dto.setServiceYears(null);
                                dto.setEntitledDays(null);
                                dto.setRemainingDays(null);
                                dto.setMessage("No join date on record — leave balance not verified.");
                                return dto;
                            }
                            dto.setEntitlementKnown(true);
                            dto.setServiceYears(serviceYears);
                            BigDecimal entitled = BigDecimal.valueOf(entitlementFor(bands, serviceYears));
                            dto.setEntitledDays(entitled);
                            BigDecimal remaining = entitled.subtract(taken);
                            dto.setRemainingDays(remaining);
                            if (bands.isEmpty()) {
                                dto.setMessage("No entitlement bands configured for this leave type.");
                            } else if (entitled.signum() == 0) {
                                dto.setMessage("Below the first entitlement band ("
                                        + serviceYears + " year(s) of service) — no annual entitlement yet.");
                            }
                            return dto;
                        });
                    }))));
    }

    /** Balance summary for the current user across every leave type that has entitlement bands
     *  (types without bands are omitted; entitlementKnown is false when there is no join date). */
    public Uni<List<LeaveBalanceDTO>> getBalances(String requestedStaffId) {
        return resolveStaffId(requestedStaffId).flatMap(staffId ->
            staffRepo.findByStaffId(staffId).flatMap(staff ->
                leaveTypeRepo.findAllOptions().flatMap(types ->
                    leaveTypeRepo.findAllEntitlements().flatMap(allBands -> {
                        int year = DateUtil.nowSGT().getYear();
                        LocalDate start = LocalDate.of(year, 1, 1);
                        LocalDate end   = LocalDate.of(year, 12, 31);
                        return leaveRepo.sumBookedDaysByTypeAndStatus(staffId, start, end).map(rows -> {
                            Map<String, List<LeaveTypeEntitlement>> bandsByType = new LinkedHashMap<>();
                            for (LeaveTypeEntitlement b : allBands) {
                                bandsByType.computeIfAbsent(b.getLeaveType(), k -> new ArrayList<>()).add(b);
                            }
                            Map<String, BigDecimal> approvedByType = new HashMap<>();
                            Map<String, BigDecimal> pendingByType = new HashMap<>();
                            for (Object[] r : rows) {
                                String lt = (String) r[0];
                                String st = (String) r[1];
                                BigDecimal sum = r[2] == null ? BigDecimal.ZERO : (BigDecimal) r[2];
                                if (STATUS_APPROVED.equals(st)) {
                                    approvedByType.merge(lt, sum, BigDecimal::add);
                                } else if (STATUS_PENDING.equals(st)) {
                                    pendingByType.merge(lt, sum, BigDecimal::add);
                                }
                            }
                            Integer serviceYears = serviceYears(staff);
                            List<LeaveBalanceDTO> out = new ArrayList<>();
                            for (DropdownOptionDTO type : types) {
                                String code = type.getValue();
                                List<LeaveTypeEntitlement> bands = bandsByType.get(code);
                                if (bands == null || bands.isEmpty()) {
                                    continue; // only types with a configured entitlement carry a balance
                                }
                                out.add(buildBalance(code, type.getLabel(), bands, serviceYears, year,
                                        approvedByType.getOrDefault(code, BigDecimal.ZERO),
                                        pendingByType.getOrDefault(code, BigDecimal.ZERO)));
                            }
                            return out;
                        });
                    }))));
    }

    /** Assembles one balance row from its parts (shared by the summary; entitlement rules match getBalance). */
    private static LeaveBalanceDTO buildBalance(String leaveType, String description,
                                                List<LeaveTypeEntitlement> bands, Integer serviceYears,
                                                int year, BigDecimal approved, BigDecimal pending) {
        LeaveBalanceDTO dto = new LeaveBalanceDTO();
        dto.setLeaveType(leaveType);
        dto.setLeaveTypeDescription(description);
        dto.setYear(year);
        dto.setApprovedDays(approved);
        dto.setPendingDays(pending);
        BigDecimal taken = approved.add(pending);
        dto.setTakenDays(taken);
        if (serviceYears == null) {
            dto.setEntitlementKnown(false);
            dto.setServiceYears(null);
            dto.setEntitledDays(null);
            dto.setRemainingDays(null);
            dto.setMessage("No join date on record — leave balance not verified.");
            return dto;
        }
        dto.setEntitlementKnown(true);
        dto.setServiceYears(serviceYears);
        BigDecimal entitled = BigDecimal.valueOf(entitlementFor(bands, serviceYears));
        dto.setEntitledDays(entitled);
        dto.setRemainingDays(entitled.subtract(taken));
        if (entitled.signum() == 0) {
            dto.setMessage("Below the first entitlement band ("
                    + serviceYears + " year(s) of service) — no annual entitlement yet.");
        }
        return dto;
    }

    // ─────────────────────────────────────────────────────────
    //  CANCEL — list the staff's cancelable leaves (Step 3, Cancel)
    // ─────────────────────────────────────────────────────────

    public Uni<List<LeaveApplicationDTO>> getCancelable(String requestedStaffId, String leaveType) {
        if (leaveType == null || leaveType.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("leaveType is required"));
        }
        return resolveStaffId(requestedStaffId).flatMap(staffId ->
            leaveRepo.findCancelable(staffId, leaveType)
                .map(list -> list.stream().map(this::toDtoBasic).toList()));
    }

    // ─────────────────────────────────────────────────────────
    //  READ
    // ─────────────────────────────────────────────────────────

    public Uni<List<LeaveApplicationDTO>> listByStaff(String staffId) {
        return leaveRepo.findByStaff(staffId)
                .map(list -> list.stream().map(this::toDtoBasic).toList());
    }

    /** One application, with the leave-type description + approver name resolved for display. */
    public Uni<LeaveApplicationDTO> getOne(Long id) {
        return leaveRepo.findById(id).flatMap(e -> {
            if (e == null) return Uni.createFrom().nullItem();
            LeaveApplicationDTO dto = toDtoBasic(e);
            return leaveTypeRepo.findDescription(e.getLeaveType())
                .onFailure().recoverWithItem((String) null)
                .flatMap(desc -> {
                    dto.setLeaveTypeDescription(desc);
                    return staffRepo.findNameByStaffId(e.getApproverStaffId())
                        .onFailure().recoverWithItem((String) null)
                        .map(approverName -> {
                            dto.setApproverName(approverName);
                            return dto;
                        });
                });
        });
    }

    // ─────────────────────────────────────────────────────────
    //  SUBMIT (create the application → notify approver)
    // ─────────────────────────────────────────────────────────

    public Uni<LeaveApplicationDTO> submitApplication(LeaveApplicationDTO dto, DeviceInfo deviceInfo) {
        String action = normalizeAction(dto.getLeaveAction());
        if (action == null) {
            return Uni.createFrom().failure(
                    new IllegalArgumentException("I wish to (leaveAction) must be APPLY or CANCEL"));
        }
        if (dto.getLeaveType() == null || dto.getLeaveType().isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Leave Type is required"));
        }
        if (dto.getApproverStaffId() == null || dto.getApproverStaffId().isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("An approver must be selected"));
        }

        return resolveStaffId(dto.getStaffId()).flatMap(staffId ->
            staffRepo.findByStaffId(staffId).flatMap(staff ->
                buildAndValidate(action, dto, staffId, staff).flatMap(entity ->
                    Panache.withTransaction(() -> leaveRepo.save(entity))
                        .flatMap(saved -> logSubmit(saved, deviceInfo).replaceWith(saved))
                        .call(this::notifyApprover)
                        .flatMap(saved -> getOne(saved.getUniqId()))
                )));
    }

    /** Builds the entity for APPLY or CANCEL, validating the branch-specific fields. */
    private Uni<LeaveApplication> buildAndValidate(String action, LeaveApplicationDTO dto,
                                                   String staffId, Staff staff) {
        LocalDateTime now = DateUtil.nowSGT();
        LeaveApplication e = new LeaveApplication();
        e.setStaffId(staffId);
        e.setStaffName(staff != null && staff.getName() != null ? staff.getName() : staffId);
        e.setDepartment(staff != null ? staff.getDepartment() : dto.getDepartment());
        e.setApplicationDate(dto.getApplicationDate() != null ? dto.getApplicationDate() : now);
        e.setLeaveAction(action);
        e.setLeaveType(dto.getLeaveType().trim());
        e.setRemarks(dto.getRemarks());
        e.setApproverStaffId(dto.getApproverStaffId().trim());
        e.setStatus(STATUS_PENDING);
        e.setEntryStaff(staffId);
        e.setEntryDate(now);
        e.setLastEditStaff(staffId);
        e.setLastEditDate(now);

        if (ACTION_APPLY.equals(action)) {
            LocalDate from = dto.getFromDate();
            LocalDate to = dto.getToDate();
            if (from == null || to == null) {
                return Uni.createFrom().failure(
                        new IllegalArgumentException("From and To dates are required to apply for leave"));
            }
            if (to.isBefore(from)) {
                return Uni.createFrom().failure(
                        new IllegalArgumentException("To date cannot be before From date"));
            }
            String half = normalizeHalf(dto.getHalfDayPeriod());
            e.setFromDate(from);
            e.setToDate(to);
            e.setHalfDayPeriod(half);
            // Trust the user's edited value when supplied (the field is editable); otherwise
            // auto-count working days (weekends excluded; a half-day subtracts 0.5).
            BigDecimal total = dto.getTotalDays() != null
                    ? dto.getTotalDays()
                    : workingDays(from, to, half);
            e.setTotalDays(total);
            return Uni.createFrom().item(e);
        }

        // CANCEL: must reference one of this staff's own APPROVED leaves of the same type.
        Long refId = dto.getCancelRefId();
        if (refId == null) {
            return Uni.createFrom().failure(
                    new IllegalArgumentException("Select the leave to cancel"));
        }
        return leaveRepo.findById(refId).flatMap(ref -> {
            if (ref == null) {
                return Uni.createFrom().failure(new NotFoundException("Leave " + refId + " not found"));
            }
            if (!staffId.equalsIgnoreCase(ref.getStaffId())) {
                return Uni.createFrom().failure(
                        new IllegalArgumentException("You can only cancel your own leave"));
            }
            if (!STATUS_APPROVED.equalsIgnoreCase(ref.getStatus())) {
                return Uni.createFrom().failure(new IllegalArgumentException(
                        "Only an approved leave can be cancelled (current status: " + ref.getStatus() + ")"));
            }
            // Carry the referenced leave's period + days onto the cancel record for context.
            e.setCancelRefId(refId);
            e.setLeaveType(ref.getLeaveType());
            e.setFromDate(ref.getFromDate());
            e.setToDate(ref.getToDate());
            e.setHalfDayPeriod(ref.getHalfDayPeriod());
            e.setTotalDays(ref.getTotalDays());
            return Uni.createFrom().item(e);
        });
    }

    // ─────────────────────────────────────────────────────────
    //  WORKING-DAYS CALCULATION
    // ─────────────────────────────────────────────────────────

    /**
     * Working days in [from, to] excluding Sat/Sun; a half-day (AM/PM) subtracts 0.5.
     * No public-holiday calendar is applied (none is available). Returns null for an
     * invalid range. The result is a suggestion — the field stays editable.
     */
    public static BigDecimal workingDays(LocalDate from, LocalDate to, String half) {
        if (from == null || to == null || to.isBefore(from)) {
            return null;
        }
        long weekdays = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                weekdays++;
            }
        }
        BigDecimal days = BigDecimal.valueOf(weekdays);
        if (isHalf(half)) {
            days = days.subtract(HALF);
        }
        if (days.signum() < 0) {
            days = BigDecimal.ZERO;
        }
        return days.setScale(1);
    }

    // ─────────────────────────────────────────────────────────
    //  ENTITLEMENT HELPERS
    // ─────────────────────────────────────────────────────────

    /** Completed years of service today, or null when the staff has no recorded join date. */
    private static Integer serviceYears(Staff staff) {
        if (staff == null || staff.getDateJoin() == null) {
            return null;
        }
        LocalDate join = staff.getDateJoin().toLocalDate();
        LocalDate today = DateUtil.nowSGT().toLocalDate();
        if (join.isAfter(today)) {
            return 0;
        }
        return Period.between(join, today).getYears();
    }

    /**
     * Days entitled for the given years of service = the DaysOfLeave of the highest band whose
     * YearOfService ≤ serviceYears. Zero when below the first band or no bands exist.
     * Bands arrive ascending by YearOfService.
     */
    private static int entitlementFor(List<LeaveTypeEntitlement> ascendingBands, int serviceYears) {
        int days = 0;
        for (LeaveTypeEntitlement band : ascendingBands) {
            if (band.getYearOfService() != null && band.getYearOfService() <= serviceYears) {
                days = band.getDaysOfLeave() != null ? band.getDaysOfLeave() : days;
            } else {
                break; // ascending — no later band qualifies
            }
        }
        return days;
    }

    // ─────────────────────────────────────────────────────────
    //  AUDIT + NOTIFY (best-effort)
    // ─────────────────────────────────────────────────────────

    private Uni<Void> logSubmit(LeaveApplication e, DeviceInfo deviceInfo) {
        String action = ACTION_CANCEL.equals(e.getLeaveAction())
                ? UserActionLogService.Action.CANCEL_LEAVE
                : UserActionLogService.Action.APPLY_LEAVE;
        String remarks = (ACTION_CANCEL.equals(e.getLeaveAction())
                ? "Requested cancellation of " : "Applied for ")
                + nz(e.getLeaveType()) + " leave"
                + (e.getTotalDays() != null ? " (" + e.getTotalDays().toPlainString() + " day(s))" : "");
        return userActionLogService.logAction(e.getStaffId(), UserActionLogService.Module.STAFF_LEAVE,
                truncate(String.valueOf(e.getUniqId()), LEN_LOG_REFERENCE), action, deviceInfo,
                truncate(remarks, LEN_LOG_REMARKS));
    }

    /** Notify the chosen approver that a leave application awaits their decision. */
    private Uni<Void> notifyApprover(LeaveApplication e) {
        String approver = e.getApproverStaffId();
        if (approver == null || approver.isBlank()) {
            return Uni.createFrom().voidItem();
        }
        String who = (e.getStaffName() != null && !e.getStaffName().isBlank())
                ? e.getStaffName() : e.getStaffId();
        boolean cancel = ACTION_CANCEL.equals(e.getLeaveAction());
        String subject = (cancel ? "Leave cancellation request from " : "Leave application from ")
                + who + " - " + nz(e.getLeaveType());
        String desc = who + (cancel ? " has requested to cancel " : " has applied for ")
                + nz(e.getLeaveType()) + " leave"
                + periodText(e) + ". Please review and approve.";
        return Panache.withTransaction(() ->
                notificationRepo.create(MODULE_ID, NOTIF_TYPE_ADMIN,
                        truncate(subject, LEN_NOTIF_SUBJECT), truncate(desc, LEN_NOTIF_DESC),
                        approver, e.getStaffId(), String.valueOf(e.getUniqId())))
            .replaceWithVoid()
            .onFailure().recoverWithItem((Void) null);
    }

    // ─────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────

    /** Resolves the applicant: the logged-in user wins, falling back to the requested id. */
    private Uni<String> resolveStaffId(String requestedStaffId) {
        return currentUserService.getCurrentUser().flatMap(user -> {
            String staffId = (user != null && user.getStaffId() != null
                    && !CurrentUserService.SYSTEM_USER.equals(user.getStaffId()))
                    ? user.getStaffId()
                    : requestedStaffId;
            if (staffId == null || staffId.isBlank()) {
                return Uni.createFrom().failure(
                        new IllegalArgumentException("Cannot resolve the applicant (staffId)"));
            }
            return Uni.createFrom().item(staffId);
        });
    }

    private static String normalizeAction(String action) {
        if (action == null) return null;
        String a = action.trim().toUpperCase();
        return (ACTION_APPLY.equals(a) || ACTION_CANCEL.equals(a)) ? a : null;
    }

    private static String normalizeHalf(String half) {
        if (half == null) return null;
        String h = half.trim().toUpperCase();
        return (HALF_AM.equals(h) || HALF_PM.equals(h)) ? h : null;
    }

    private static boolean isHalf(String half) {
        String h = normalizeHalf(half);
        return HALF_AM.equals(h) || HALF_PM.equals(h);
    }

    private static String periodText(LeaveApplication e) {
        if (e.getFromDate() == null) return "";
        String base = " from " + e.getFromDate();
        if (e.getToDate() != null && !e.getToDate().equals(e.getFromDate())) {
            base += " to " + e.getToDate();
        }
        if (isHalf(e.getHalfDayPeriod())) {
            base += " (" + e.getHalfDayPeriod() + " half-day)";
        }
        return base;
    }

    private LeaveApplicationDTO toDtoBasic(LeaveApplication e) {
        LeaveApplicationDTO dto = new LeaveApplicationDTO();
        dto.setUniqId(e.getUniqId());
        dto.setStaffId(e.getStaffId());
        dto.setStaffName(e.getStaffName());
        dto.setDepartment(e.getDepartment());
        dto.setApplicationDate(e.getApplicationDate());
        dto.setLeaveAction(e.getLeaveAction());
        dto.setLeaveType(e.getLeaveType());
        dto.setRemarks(e.getRemarks());
        dto.setFromDate(e.getFromDate());
        dto.setToDate(e.getToDate());
        dto.setHalfDayPeriod(e.getHalfDayPeriod());
        dto.setTotalDays(e.getTotalDays());
        dto.setCancelRefId(e.getCancelRefId());
        dto.setApproverStaffId(e.getApproverStaffId());
        dto.setStatus(e.getStatus());
        dto.setApprovedBy(e.getApprovedBy());
        dto.setApprovedDate(e.getApprovedDate());
        dto.setRejectReason(e.getRejectReason());
        dto.setEntryDate(e.getEntryDate());
        dto.setLastEditDate(e.getLastEditDate());
        return dto;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
