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
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import com.aisolutions.shared.util.DateUtil;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Row;
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

@ApplicationScoped
public class LeaveService {

    public static final String ACTION_APPLY  = "APPLY";
    public static final String ACTION_CANCEL = "CANCEL";

    public static final String STATUS_PENDING   = "PENDING";
    public static final String STATUS_APPROVED  = "APPROVED";
    public static final String STATUS_REJECTED  = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private static final String HALF_AM = "AM";
    private static final String HALF_PM = "PM";
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private static final String MODULE_ID = "mod18";
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
    @Inject CompanyPoolManager companyPoolManager;

    /** Step 1 prefill: the current user's name + department (locked fields). */
    public Uni<StaffProfileDTO> getProfile(String requestedStaffId) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            resolveStaffId(requestedStaffId).flatMap(staffId ->
                staffRepo.findByStaffId(pool, staffId).map(s -> {
                    if (s == null) {
                        return new StaffProfileDTO(staffId, staffId, null);
                    }
                    String name = (s.getName() != null && !s.getName().isBlank()) ? s.getName() : staffId;
                    return new StaffProfileDTO(staffId, name, s.getDepartment());
                })));
    }

    public Uni<List<DropdownOptionDTO>> getLeaveTypeOptions() {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> leaveTypeRepo.findAllOptions(pool));
    }

    public Uni<List<DropdownOptionDTO>> getApproverOptions() {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> staffRepo.findApproverOptions(pool));
    }

    public Uni<LeaveBalanceDTO> getBalance(String requestedStaffId, String leaveType) {
        if (leaveType == null || leaveType.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("leaveType is required"));
        }
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> resolveStaffId(requestedStaffId)
                .flatMap(staffId -> staffRepo.findByStaffId(pool, staffId)
                    .flatMap(staff -> getSingleBalance(pool, staffId, staff, leaveType))));
    }

    private Uni<LeaveBalanceDTO> getSingleBalance(io.vertx.mutiny.sqlclient.SqlClient pool,
                                                   String staffId, Staff staff, String leaveType) {
        return leaveTypeRepo.findDescription(pool, leaveType)
            .flatMap(desc -> leaveTypeRepo.findEntitlements(pool, leaveType)
                .flatMap(bands -> {
                    int year = DateUtil.nowSGT().getYear();
                    LocalDate start = LocalDate.of(year, 1, 1);
                    LocalDate end   = LocalDate.of(year, 12, 31);
                    return leaveRepo.sumBookedDays(pool, staffId, leaveType, start, end)
                        .map(taken -> buildSingleBalance(staff, leaveType, desc, bands, year, taken));
                }));
    }

    private LeaveBalanceDTO buildSingleBalance(Staff staff, String leaveType, String desc,
                                                List<LeaveTypeEntitlement> bands, int year, BigDecimal taken) {
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
    }

    public Uni<List<LeaveBalanceDTO>> getBalances(String requestedStaffId) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> resolveStaffId(requestedStaffId)
                .flatMap(staffId -> staffRepo.findByStaffId(pool, staffId)
                    .flatMap(staff -> computeAllBalances(pool, staffId, staff))));
    }

    private Uni<List<LeaveBalanceDTO>> computeAllBalances(io.vertx.mutiny.sqlclient.SqlClient pool,
                                                           String staffId, Staff staff) {
        return leaveTypeRepo.findAllOptions(pool)
            .flatMap(types -> leaveTypeRepo.findAllEntitlements(pool)
                .flatMap(allBands -> {
                    int year = DateUtil.nowSGT().getYear();
                    LocalDate start = LocalDate.of(year, 1, 1);
                    LocalDate end   = LocalDate.of(year, 12, 31);
                    return leaveRepo.sumBookedDaysByTypeAndStatus(pool, staffId, start, end)
                        .map(rows -> aggregateBalances(staff, types, allBands, year, rows));
                }));
    }

    private List<LeaveBalanceDTO> aggregateBalances(Staff staff, List<DropdownOptionDTO> types,
                                                     List<LeaveTypeEntitlement> allBands, int year,
                                                     List<Row> rows) {
        Map<String, List<LeaveTypeEntitlement>> bandsByType = new LinkedHashMap<>();
        for (LeaveTypeEntitlement b : allBands) {
            bandsByType.computeIfAbsent(b.getLeaveType(), k -> new ArrayList<>()).add(b);
        }
        Map<String, BigDecimal> approvedByType = new HashMap<>();
        Map<String, BigDecimal> pendingByType = new HashMap<>();
        for (Row r : rows) {
            String lt = r.getString("LeaveType");
            String st = r.getString("Status");
            BigDecimal sum = r.getBigDecimal("total");
            if (sum == null) sum = BigDecimal.ZERO;
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
            if (bands == null || bands.isEmpty()) continue;
            out.add(buildBalance(code, type.getLabel(), bands, serviceYears, year,
                    approvedByType.getOrDefault(code, BigDecimal.ZERO),
                    pendingByType.getOrDefault(code, BigDecimal.ZERO)));
        }
        return out;
    }

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

    public Uni<List<LeaveApplicationDTO>> getCancelable(String requestedStaffId, String leaveType) {
        if (leaveType == null || leaveType.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("leaveType is required"));
        }
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            resolveStaffId(requestedStaffId).flatMap(staffId ->
                leaveRepo.findCancelable(pool, staffId, leaveType)
                    .map(list -> list.stream().map(this::toDtoBasic).toList())));
    }

    public Uni<List<LeaveApplicationDTO>> listByStaff(String staffId) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            leaveRepo.findByStaff(pool, staffId)
                .map(list -> list.stream().map(this::toDtoBasic).toList()));
    }

    public Uni<LeaveApplicationDTO> getOne(Long id) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            leaveRepo.findById(pool, id).flatMap(e -> {
                if (e == null) return Uni.createFrom().nullItem();
                LeaveApplicationDTO dto = toDtoBasic(e);
                return leaveTypeRepo.findDescription(pool, e.getLeaveType())
                    .onFailure().recoverWithItem((String) null)
                    .flatMap(desc -> {
                        dto.setLeaveTypeDescription(desc);
                        return staffRepo.findNameByStaffId(pool, e.getApproverStaffId())
                            .onFailure().recoverWithItem((String) null)
                            .map(approverName -> {
                                dto.setApproverName(approverName);
                                return dto;
                            });
                    });
            }));
    }

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

        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> resolveStaffId(dto.getStaffId())
                .flatMap(staffId -> staffRepo.findByStaffId(pool, staffId)
                    .flatMap(staff -> buildAndValidate(action, dto, staffId, staff, pool)
                        .flatMap(entity -> saveAndNotify(pool, entity, deviceInfo)))));
    }

    private Uni<LeaveApplicationDTO> saveAndNotify(io.vertx.mutiny.mysqlclient.MySQLPool pool,
                                                    LeaveApplication entity, DeviceInfo deviceInfo) {
        return pool.withTransaction(tx -> leaveRepo.save(tx, entity))
            .flatMap(saved -> logSubmit(saved, deviceInfo).replaceWith(saved))
            .call(this::notifyApprover)
            .flatMap(saved -> getOne(saved.getUniqId()));
    }

    private Uni<LeaveApplication> buildAndValidate(String action, LeaveApplicationDTO dto,
                                                   String staffId, Staff staff,
                                                   io.vertx.mutiny.sqlclient.SqlClient pool) {
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
            BigDecimal total = dto.getTotalDays() != null
                    ? dto.getTotalDays()
                    : workingDays(from, to, half);
            e.setTotalDays(total);
            return Uni.createFrom().item(e);
        }

        Long refId = dto.getCancelRefId();
        if (refId == null) {
            return Uni.createFrom().failure(
                    new IllegalArgumentException("Select the leave to cancel"));
        }
        return leaveRepo.findById(pool, refId).flatMap(ref -> {
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
            e.setCancelRefId(refId);
            e.setLeaveType(ref.getLeaveType());
            e.setFromDate(ref.getFromDate());
            e.setToDate(ref.getToDate());
            e.setHalfDayPeriod(ref.getHalfDayPeriod());
            e.setTotalDays(ref.getTotalDays());
            return Uni.createFrom().item(e);
        });
    }

    public static BigDecimal workingDays(LocalDate from, LocalDate to, String half) {
        if (from == null || to == null || to.isBefore(from)) return null;
        long weekdays = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) weekdays++;
        }
        BigDecimal days = BigDecimal.valueOf(weekdays);
        if (isHalf(half)) days = days.subtract(HALF);
        if (days.signum() < 0) days = BigDecimal.ZERO;
        return days.setScale(1);
    }

    private static Integer serviceYears(Staff staff) {
        if (staff == null || staff.getDateJoin() == null) return null;
        LocalDate join = staff.getDateJoin().toLocalDate();
        LocalDate today = DateUtil.nowSGT().toLocalDate();
        if (join.isAfter(today)) return 0;
        return Period.between(join, today).getYears();
    }

    private static int entitlementFor(List<LeaveTypeEntitlement> ascendingBands, int serviceYears) {
        int days = 0;
        for (LeaveTypeEntitlement band : ascendingBands) {
            if (band.getYearOfService() != null && band.getYearOfService() <= serviceYears) {
                days = band.getDaysOfLeave() != null ? band.getDaysOfLeave() : days;
            } else {
                break;
            }
        }
        return days;
    }

    private Uni<Void> logSubmit(LeaveApplication e, DeviceInfo deviceInfo) {
        // Applying and cancelling both create a leave record, so both log as ADD;
        // the remark carries which one it was.
        String action = UserActionLogService.Action.ADD;
        String remarks = (ACTION_CANCEL.equals(e.getLeaveAction())
                ? "Requested cancellation of " : "Applied for ")
                + nz(e.getLeaveType()) + " leave"
                + (e.getTotalDays() != null ? " (" + e.getTotalDays().toPlainString() + " day(s))" : "");
        return userActionLogService.logAction(
                currentUserService.getCurrentCompanyId(), e.getStaffId(), UserActionLogService.Module.STAFF_LEAVE,
                truncate(String.valueOf(e.getUniqId()), LEN_LOG_REFERENCE), action, deviceInfo,
                truncate(remarks, LEN_LOG_REMARKS));
    }

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
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
                pool.withTransaction(tx ->
                    notificationRepo.create(tx, MODULE_ID, NOTIF_TYPE_ADMIN,
                            truncate(subject, LEN_NOTIF_SUBJECT), truncate(desc, LEN_NOTIF_DESC),
                            approver, e.getStaffId(), String.valueOf(e.getUniqId()))))
            .replaceWithVoid()
            .onFailure().recoverWithItem((Void) null);
    }

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

    private static String nz(String s) { return s == null ? "" : s; }
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
