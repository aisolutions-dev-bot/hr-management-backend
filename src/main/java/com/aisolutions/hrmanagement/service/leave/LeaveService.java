package com.aisolutions.hrmanagement.service.leave;

import com.aisolutions.hrmanagement.dto.ApprovalFlowDTO;
import com.aisolutions.hrmanagement.dto.ApprovalTrailDTO;
import com.aisolutions.hrmanagement.dto.DropdownOptionDTO;
import com.aisolutions.hrmanagement.dto.LeaveApplicationDTO;
import com.aisolutions.hrmanagement.dto.LeaveBalanceDTO;
import com.aisolutions.hrmanagement.dto.LeaveLedgerRow;
import com.aisolutions.hrmanagement.dto.StaffProfileDTO;
import com.aisolutions.hrmanagement.entity.LeaveApplication;
import com.aisolutions.hrmanagement.entity.LeaveTypeEntitlement;
import com.aisolutions.hrmanagement.entity.Staff;
import com.aisolutions.hrmanagement.repository.LeaveApplicationRepository;
import com.aisolutions.hrmanagement.repository.LeaveLedgerRepository;
import com.aisolutions.hrmanagement.repository.LeavePolicyRepository;
import com.aisolutions.hrmanagement.repository.LeaveTypeRepository;
import com.aisolutions.hrmanagement.repository.NotificationRepository;
import com.aisolutions.hrmanagement.service.leave.LeaveEntitlementCalculator.Policy;
import com.aisolutions.hrmanagement.repository.StaffRepository;
import com.aisolutions.hrmanagement.service.approval.ApprovalFlowService;
import com.aisolutions.hrmanagement.service.CurrentUserService;
import com.aisolutions.hrmanagement.service.SystemParameterService;
import com.aisolutions.hrmanagement.service.email.EmailNotificationService;
import com.aisolutions.hrmanagement.service.sms.SmsNotificationService;
import com.aisolutions.hrmanagement.service.whatsapp.WhatsappNotificationService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    @Inject LeavePolicyRepository leavePolicyRepo;
    @Inject LeaveLedgerRepository ledgerRepo;
    @Inject StaffRepository staffRepo;
    @Inject CurrentUserService currentUserService;
    @Inject UserActionLogService userActionLogService;
    @Inject NotificationRepository notificationRepo;
    @Inject CompanyPoolManager companyPoolManager;
    @Inject SystemParameterService systemParameterService;
    @Inject EmailNotificationService emailNotificationService;
    @Inject SmsNotificationService smsNotificationService;
    @Inject WhatsappNotificationService whatsappNotificationService;
    @Inject ApprovalFlowService approvalFlowService;

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
                .flatMap(bands -> leaveTypeRepo.isEligibleOnRequest(pool, leaveType)
                    .flatMap(onRequest -> leaveTypeRepo.findProRateMethod(pool, leaveType)
                        .flatMap(method -> leavePolicyRepo.findPolicy(pool)
                            .flatMap(policy -> {
                                int year = DateUtil.nowSGT().getYear();
                                LocalDate today = DateUtil.nowSGT().toLocalDate();
                                LocalDate start = LocalDate.of(year, 1, 1);
                                LocalDate end   = LocalDate.of(year, 12, 31);
                                // Grant + used days come from the ledger (FIFO/expiry); pending is a live soft hold.
                                return leaveRepo.sumPendingDays(pool, staffId, leaveType, start, end)
                                    .flatMap(pending -> leaveTypeRepo.findCarryForwardCap(pool, leaveType)
                                        .flatMap(cap -> ledgerRepo.findRows(pool, staffId, leaveType)
                                            .onFailure().recoverWithItem(List.<LeaveLedgerRow>of())
                                            .map(rows -> buildSingleBalance(staff, leaveType, desc, bands, year, today,
                                                    pending, LeaveBalanceCalculator.compute(rows, cap, year, today),
                                                    Boolean.TRUE.equals(onRequest), method, policy))));
                            })))));
    }

    private LeaveBalanceDTO buildSingleBalance(Staff staff, String leaveType, String desc,
                                                List<LeaveTypeEntitlement> bands, int year, LocalDate today,
                                                BigDecimal pending, LeaveBalanceCalculator.Result res,
                                                boolean onRequest, String method, Policy policy) {
        LeaveBalanceDTO dto = new LeaveBalanceDTO();
        dto.setLeaveType(leaveType);
        dto.setLeaveTypeDescription(desc);
        dto.setYear(year);
        BigDecimal approved = nz(res.approved());
        BigDecimal taken = approved.add(pending);
        dto.setApprovedDays(approved);
        dto.setPendingDays(pending);
        dto.setTakenDays(taken);
        // The ledger's assigned entitlement (GRANT buckets) wins over the ladder when present.
        if (res.hasGrant()) {
            applyLedgerBalance(dto, res, pending);
            return dto;
        }
        // On-request type with no HR-assigned record: not auto-entitled from the ladder.
        if (onRequest) {
            dto.setEntitlementKnown(false);
            dto.setServiceYears(null);
            dto.setEntitledDays(null);
            dto.setRemainingDays(null);
            dto.setEntitlementSource("REQUEST");
            dto.setMessage("Granted on request — no entitlement assigned yet.");
            return dto;
        }
        dto.setEntitlementSource("LADDER");
        // Pre-assignment estimate: pro-ration-aware so it matches the grant HR later assigns
        // (MONTH-unit step bands like MC are read as service months, not years).
        LeaveEntitlementCalculator.Suggestion sug =
                LeaveEntitlementCalculator.compute(method, bands, joinDate(staff), year, today, policy);
        if (!sug.entitlementKnown()) {
            dto.setEntitlementKnown(false);
            dto.setServiceYears(null);
            dto.setEntitledDays(null);
            dto.setRemainingDays(null);
            dto.setMessage("No join date on record — leave balance not verified.");
            return dto;
        }
        dto.setEntitlementKnown(true);
        dto.setServiceYears(sug.serviceYears());
        BigDecimal entitled = sug.days();
        dto.setEntitledDays(entitled);
        dto.setRemainingDays(entitled.subtract(taken));
        if (bands.isEmpty()) {
            dto.setMessage("No entitlement bands configured for this leave type.");
        } else if (entitled.signum() == 0) {
            dto.setMessage(sug.note() != null ? sug.note()
                    : "Below the first entitlement band — no annual entitlement yet.");
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
                .flatMap(allBands -> leaveTypeRepo.findEligibleOnRequestCodes(pool)
                    .flatMap(onRequestCodes -> leaveTypeRepo.findCarryForwardCaps(pool)
                        .flatMap(caps -> leaveTypeRepo.findProRateMethods(pool)
                            .flatMap(methods -> leavePolicyRepo.findPolicy(pool)
                                .flatMap(policy -> {
                                    int year = DateUtil.nowSGT().getYear();
                                    LocalDate today = DateUtil.nowSGT().toLocalDate();
                                    LocalDate start = LocalDate.of(year, 1, 1);
                                    LocalDate end   = LocalDate.of(year, 12, 31);
                                    // Pending (live soft hold) from the applications; grant + used from the ledger.
                                    return leaveRepo.sumBookedDaysByTypeAndStatus(pool, staffId, start, end)
                                        .flatMap(prows -> ledgerRepo.findRowsByStaff(pool, staffId)
                                            .onFailure().recoverWithItem(List.<LeaveLedgerRow>of())
                                            .map(ledgerRows -> aggregateBalances(staff, types, allBands, year, today,
                                                    prows, ledgerRows, caps, onRequestCodes, methods, policy)));
                                }))))));
    }

    private List<LeaveBalanceDTO> aggregateBalances(Staff staff, List<DropdownOptionDTO> types,
                                                     List<LeaveTypeEntitlement> allBands, int year, LocalDate today,
                                                     List<Row> prows, List<LeaveLedgerRow> ledgerRows,
                                                     Map<String, Integer> caps, Set<String> onRequestCodes,
                                                     Map<String, String> methods, Policy policy) {
        Map<String, List<LeaveTypeEntitlement>> bandsByType = new LinkedHashMap<>();
        for (LeaveTypeEntitlement b : allBands) {
            bandsByType.computeIfAbsent(b.getLeaveType(), k -> new ArrayList<>()).add(b);
        }
        Map<String, BigDecimal> pendingByType = new HashMap<>();
        for (Row r : prows) {
            if (!STATUS_PENDING.equals(r.getString("Status"))) continue;
            String lt = r.getString("LeaveType");
            BigDecimal sum = r.getBigDecimal("total");
            pendingByType.merge(lt, sum != null ? sum : BigDecimal.ZERO, BigDecimal::add);
        }
        Map<String, List<LeaveLedgerRow>> rowsByType = new LinkedHashMap<>();
        for (LeaveLedgerRow r : ledgerRows) {
            rowsByType.computeIfAbsent(r.leaveType(), k -> new ArrayList<>()).add(r);
        }
        LocalDate joinDate = joinDate(staff);
        List<LeaveBalanceDTO> out = new ArrayList<>();
        for (DropdownOptionDTO type : types) {
            String code = type.getValue();
            List<LeaveTypeEntitlement> bands = bandsByType.get(code);
            boolean hasBands = bands != null && !bands.isEmpty();
            LeaveBalanceCalculator.Result res = LeaveBalanceCalculator.compute(
                    rowsByType.getOrDefault(code, List.of()), caps.get(code), year, today);
            // Without a GRANT bucket: an on-request type never auto-shows (regardless of any ladder
            // bands left on it), and a type with no ladder band has nothing to show.
            if (!res.hasGrant() && (onRequestCodes.contains(code) || !hasBands)) continue;
            out.add(buildBalance(code, type.getLabel(), hasBands ? bands : List.of(), joinDate, year, today,
                    res, pendingByType.getOrDefault(code, BigDecimal.ZERO),
                    methods.getOrDefault(code, LeaveEntitlementCalculator.METHOD_NONE), policy));
        }
        return out;
    }

    private static LeaveBalanceDTO buildBalance(String leaveType, String description,
                                                List<LeaveTypeEntitlement> bands, LocalDate joinDate,
                                                int year, LocalDate today, LeaveBalanceCalculator.Result res,
                                                BigDecimal pending, String method, Policy policy) {
        LeaveBalanceDTO dto = new LeaveBalanceDTO();
        dto.setLeaveType(leaveType);
        dto.setLeaveTypeDescription(description);
        dto.setYear(year);
        BigDecimal approved = nz(res.approved());
        BigDecimal taken = approved.add(pending);
        dto.setApprovedDays(approved);
        dto.setPendingDays(pending);
        dto.setTakenDays(taken);
        // The ledger's assigned entitlement (GRANT buckets) wins over the ladder when present.
        if (res.hasGrant()) {
            applyLedgerBalance(dto, res, pending);
            return dto;
        }
        dto.setEntitlementSource("LADDER");
        // Pre-assignment estimate: pro-ration-aware so it matches the grant HR later assigns
        // (MONTH-unit step bands like MC are read as service months, not years).
        LeaveEntitlementCalculator.Suggestion sug =
                LeaveEntitlementCalculator.compute(method, bands, joinDate, year, today, policy);
        if (!sug.entitlementKnown()) {
            dto.setEntitlementKnown(false);
            dto.setServiceYears(null);
            dto.setEntitledDays(null);
            dto.setRemainingDays(null);
            dto.setMessage("No join date on record — leave balance not verified.");
            return dto;
        }
        dto.setEntitlementKnown(true);
        dto.setServiceYears(sug.serviceYears());
        BigDecimal entitled = sug.days();
        dto.setEntitledDays(entitled);
        dto.setRemainingDays(entitled.subtract(taken));
        if (entitled.signum() == 0) {
            dto.setMessage(sug.note() != null ? sug.note()
                    : "Below the first entitlement band — no annual entitlement yet.");
        }
        return dto;
    }

    /**
     * Apply a ledger-computed balance (GRANT buckets, with carry-forward and expiry): it is
     * authoritative and overrides the ladder, so it also clears the "no join date — not verified"
     * state for manual-entry staff.
     */
    private static void applyLedgerBalance(LeaveBalanceDTO dto, LeaveBalanceCalculator.Result res, BigDecimal pending) {
        dto.setEntitlementKnown(true);
        dto.setServiceYears(res.serviceYears());   // snapshot from the year's grant; may be null
        dto.setEntitledDays(nz(res.entitled()));
        dto.setBroughtForwardDays(nz(res.broughtForward()));
        dto.setRemainingDays(nz(res.available()).subtract(pending));
        dto.setExpiringDays(nz(res.expiring()));
        dto.setExpiryDate(res.nextExpiry());
        dto.setLapsedDays(nz(res.lapsed()));
        dto.setEntitlementSource("ASSIGNED");
        dto.setMessage(null);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
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
                            .flatMap(approverName -> {
                                dto.setApproverName(approverName);
                                // Resolve the decider's name too (approvedBy holds their staff id).
                                if (e.getApprovedBy() == null || e.getApprovedBy().isBlank()) {
                                    return Uni.createFrom().item(dto);
                                }
                                return staffRepo.findNameByStaffId(pool, e.getApprovedBy())
                                    .onFailure().recoverWithItem((String) null)
                                    .map(decidedByName -> {
                                        dto.setApprovedByName(decidedByName);
                                        return dto;
                                    });
                            });
                    });
            }));
    }

    /** Whether an approval flow governs leave — the apply form hides the approver step when true. */
    public Uni<Boolean> isApprovalFlowActive() {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> approvalFlowService.isFlowActive(pool));
    }

    /**
     * The approval flow the apply form shows the applicant: when active, its ordered
     * tiers + approvers (the applicant does not choose one); otherwise inactive.
     */
    public Uni<ApprovalFlowDTO> getApprovalFlow() {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> approvalFlowService.flowChain(pool))
            .map(chain -> {
                List<ApprovalFlowDTO.Tier> tiers = chain.tiers().stream()
                    .map(t -> new ApprovalFlowDTO.Tier(t.level(), t.staffId(),
                            (t.staffName() != null && !t.staffName().isBlank()) ? t.staffName() : t.staffId(),
                            t.dept()))
                    .toList();
                return new ApprovalFlowDTO(chain.active(), chain.mode(), tiers);
            });
    }

    /** The approval trail for a leave, read from the m07ApprovalAction log (or the leave's own fields for legacy records). */
    public Uni<ApprovalTrailDTO> getApprovalTrail(Long id) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            leaveRepo.findById(pool, id).flatMap(e ->
                e == null ? Uni.createFrom().nullItem() : approvalFlowService.buildTrail(pool, e)));
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

        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> approvalFlowService.initialPendingApprovers(pool).flatMap(flowApprovers -> {
                // A configured flow decides the approver, so the applicant need not pick one; only the
                // no-flow (fallback) path still requires a chosen approver. When a flow is active we seed
                // the approver field with its first pending tier (a non-null pointer + fallback filter).
                boolean flowActive = flowApprovers != null && !flowApprovers.isEmpty();
                String chosen = dto.getApproverStaffId();
                if (chosen == null || chosen.isBlank()) {
                    if (flowActive) {
                        dto.setApproverStaffId(flowApprovers.get(0));
                    } else {
                        return Uni.createFrom().<LeaveApplicationDTO>failure(
                                new IllegalArgumentException("An approver must be selected"));
                    }
                }
                return resolveStaffId(dto.getStaffId())
                    .flatMap(staffId -> staffRepo.findByStaffId(pool, staffId)
                        .flatMap(staff -> buildAndValidate(action, dto, staffId, staff, pool)
                            .flatMap(entity -> ACTION_APPLY.equals(entity.getLeaveAction())
                                    ? validateApplyEligibility(pool, staff, entity.getLeaveType())
                                            .replaceWith(entity)
                                    : Uni.createFrom().item(entity))
                            .flatMap(entity -> saveAndNotify(pool, entity, deviceInfo))));
            }));
    }

    private Uni<LeaveApplicationDTO> saveAndNotify(io.vertx.mutiny.sqlclient.Pool pool,
                                                    LeaveApplication entity, DeviceInfo deviceInfo) {
        return pool.withTransaction(tx -> leaveRepo.save(tx, entity)
                // Freeze the current flow onto this leave so later flow edits won't affect it.
                .flatMap(saved -> approvalFlowService.snapshotAtSubmit(tx, saved.getUniqId(),
                            saved.getEntryStaff() != null ? saved.getEntryStaff() : saved.getStaffId(),
                            DateUtil.nowSGT())
                        .replaceWith(saved)))
            .flatMap(saved -> logSubmit(saved, deviceInfo).replaceWith(saved))
            .flatMap(saved -> resolveSubmitRecipients(pool, saved)
                .call(recipients -> notifyApprovers(saved, recipients))
                // Email/SMS are fire-and-forget on the tenant pool so the response never waits
                // on the provider.
                .invoke(recipients -> {
                    emailApprovers(pool, saved, recipients).subscribe().with(ignored -> {}, err -> {});
                    smsApprovers(pool, saved, recipients).subscribe().with(ignored -> {}, err -> {});
                    whatsappApprovers(pool, saved, recipients).subscribe().with(ignored -> {}, err -> {});
                })
                .replaceWith(saved))
            .flatMap(saved -> getOne(saved.getUniqId()));
    }

    /**
     * Who to notify on submit: the approval flow's first pending approver(s) when a
     * flow is active for mod18 + LeaveApplication, otherwise the applicant-chosen
     * approver (fallback — today's behaviour when no flow is configured).
     */
    private Uni<List<String>> resolveSubmitRecipients(io.vertx.mutiny.sqlclient.SqlClient pool,
                                                       LeaveApplication saved) {
        return approvalFlowService.initialPendingApprovers(pool).map(flowApprovers -> {
            if (flowApprovers != null && !flowApprovers.isEmpty()) {
                return flowApprovers;
            }
            String chosen = saved.getApproverStaffId();
            return (chosen == null || chosen.isBlank()) ? List.<String>of() : List.of(chosen);
        });
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

    /**
     * Apply-time eligibility gate: a pro-rated leave type ({@code ProRateMethod ≠ NONE}) requires
     * the policy's minimum months of service, so a staff below it — with no entitlement yet — is
     * rejected. Over-balance is deliberately NOT blocked here: the wizard warns and lets the
     * manager decide, and the advanced/unpaid-leave overflow feature will route any excess.
     * Cancellations are never gated.
     */
    private Uni<Void> validateApplyEligibility(io.vertx.mutiny.sqlclient.SqlClient pool,
                                               Staff staff, String leaveType) {
        return leaveTypeRepo.findProRateMethod(pool, leaveType).flatMap(method ->
            leavePolicyRepo.findPolicy(pool).flatMap(policy -> {
                LocalDate join = joinDate(staff);
                if (!LeaveEntitlementCalculator.METHOD_NONE.equals(method) && join != null) {
                    int months = LeaveEntitlementCalculator.completedMonths(join, DateUtil.nowSGT().toLocalDate());
                    if (months < policy.eligibilityMonths()) {
                        return Uni.createFrom().<Void>failure(new IllegalArgumentException(
                                "Not yet eligible for " + leaveType + " leave — a minimum of "
                                + policy.eligibilityMonths() + " months of service is required (currently "
                                + months + ")."));
                    }
                }
                return Uni.createFrom().voidItem();
            }));
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

    private static LocalDate joinDate(Staff staff) {
        return (staff != null && staff.getDateJoin() != null) ? staff.getDateJoin().toLocalDate() : null;
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

    private Uni<Void> notifyApprovers(LeaveApplication e, List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) {
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
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool -> {
            Uni<Void> chain = Uni.createFrom().voidItem();
            for (String approver : recipients) {
                if (approver == null || approver.isBlank()) continue;
                chain = chain.flatMap(v -> pool.withTransaction(tx ->
                        notificationRepo.create(tx, MODULE_ID, NOTIF_TYPE_ADMIN,
                                truncate(subject, LEN_NOTIF_SUBJECT), truncate(desc, LEN_NOTIF_DESC),
                                approver, e.getStaffId(), String.valueOf(e.getUniqId())))
                    .replaceWithVoid());
            }
            return chain;
        }).onFailure().recoverWithItem((Void) null);
    }

    /**
     * Emails each pending approver the same "please review and approve" message as
     * the in-app bell, gated once by the NOTIFICATION-EMAIL parameter (and per
     * approver by having an email on file). Best-effort: any failure is swallowed
     * so a mail problem never affects the submission.
     */
    private Uni<Void> emailApprovers(io.vertx.mutiny.sqlclient.Pool pool, LeaveApplication e,
                                     List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return systemParameterService.isNotificationEmailEnabled(pool).flatMap(enabled -> {
            if (!Boolean.TRUE.equals(enabled)) {
                return Uni.createFrom().voidItem();
            }
            Uni<Void> chain = Uni.createFrom().voidItem();
            for (String approver : recipients) {
                if (approver == null || approver.isBlank()) continue;
                chain = chain.flatMap(v -> emailOneApprover(pool, e, approver));
            }
            return chain;
        }).onFailure().recoverWithItem((Void) null);
    }

    /** Sends the submit email to one approver; NOTIFICATION-EMAIL gating is done by the caller. */
    private Uni<Void> emailOneApprover(io.vertx.mutiny.sqlclient.Pool pool, LeaveApplication e, String approver) {
        return staffRepo.findByStaffId(pool, approver).flatMap(approverStaff -> {
            String email = approverStaff != null ? approverStaff.getEmailCompany() : null;
            if (email == null || email.isBlank()) {
                return Uni.createFrom().voidItem();
            }
            String who = (e.getStaffName() != null && !e.getStaffName().isBlank())
                    ? e.getStaffName() : e.getStaffId();
            String approverName = (approverStaff.getName() != null && !approverStaff.getName().isBlank())
                    ? approverStaff.getName() : approver;
            boolean cancel = ACTION_CANCEL.equals(e.getLeaveAction());
            String subject = (cancel ? "Leave cancellation request from " : "Leave application from ")
                    + who + " - " + nz(e.getLeaveType());
            String html = LeaveEmailTemplate.buildSubmittedEmail(
                    approverName, who, nz(e.getLeaveType()), periodText(e), cancel, e.getRemarks());
            return emailNotificationService.sendReactive(email, subject, html).replaceWithVoid();
        }).onFailure().recoverWithItem((Void) null);
    }

    /** SMS counterpart of {@link #emailApprovers} — NOTIFICATION-SMS gated once. */
    private Uni<Void> smsApprovers(io.vertx.mutiny.sqlclient.Pool pool, LeaveApplication e,
                                   List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return systemParameterService.isNotificationSmsEnabled(pool).flatMap(enabled -> {
            if (!Boolean.TRUE.equals(enabled)) {
                System.err.println("[SMS] leave-submitted skipped for leave " + e.getUniqId()
                        + ": NOTIFICATION-SMS is off");
                return Uni.createFrom().voidItem();
            }
            Uni<Void> chain = Uni.createFrom().voidItem();
            for (String approver : recipients) {
                if (approver == null || approver.isBlank()) continue;
                chain = chain.flatMap(v -> smsOneApprover(pool, e, approver));
            }
            return chain;
        }).onFailure().recoverWithItem((Void) null);
    }

    /** Sends the submit SMS to one approver; NOTIFICATION-SMS gating is done by the caller. */
    private Uni<Void> smsOneApprover(io.vertx.mutiny.sqlclient.Pool pool, LeaveApplication e, String approver) {
        return staffRepo.findByStaffId(pool, approver).flatMap(approverStaff -> {
            String mobile = approverStaff != null ? approverStaff.getTelMobile() : null;
            if (mobile == null || mobile.isBlank()) {
                System.err.println("[SMS] leave-submitted skipped for leave " + e.getUniqId()
                        + ": approver " + approver + " has no TelMobile");
                return Uni.createFrom().voidItem();
            }
            String who = (e.getStaffName() != null && !e.getStaffName().isBlank())
                    ? e.getStaffName() : e.getStaffId();
            boolean cancel = ACTION_CANCEL.equals(e.getLeaveAction());
            String text = (cancel ? "Leave cancellation request from " : "Leave application from ")
                    + who + " - " + nz(e.getLeaveType()) + periodText(e) + ". - AI Solutions";
            System.err.println("[SMS] leave-submitted sending to " + mobile + " for leave " + e.getUniqId());
            return smsNotificationService.sendReactive(mobile, text)
                    .invoke(sent -> System.err.println("[SMS] leave-submitted send "
                            + (sent ? "OK" : "FAILED") + " to " + mobile + " for leave " + e.getUniqId()))
                    .replaceWithVoid();
        }).onFailure().invoke(err -> System.err.println(
                "[SMS] leave-submitted send failed for leave " + e.getUniqId() + ": " + err))
          .onFailure().recoverWithItem((Void) null);
    }

    /** WhatsApp counterpart of {@link #smsApprovers} — NOTIFICATION-WHATSAPP gated once. */
    private Uni<Void> whatsappApprovers(io.vertx.mutiny.sqlclient.Pool pool, LeaveApplication e,
                                        List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return systemParameterService.isNotificationWhatsappEnabled(pool).flatMap(enabled -> {
            if (!Boolean.TRUE.equals(enabled)) {
                System.err.println("[WhatsApp] leave-submitted skipped for leave " + e.getUniqId()
                        + ": NOTIFICATION-WHATSAPP is off");
                return Uni.createFrom().voidItem();
            }
            Uni<Void> chain = Uni.createFrom().voidItem();
            for (String approver : recipients) {
                if (approver == null || approver.isBlank()) continue;
                chain = chain.flatMap(v -> whatsappOneApprover(pool, e, approver));
            }
            return chain;
        }).onFailure().recoverWithItem((Void) null);
    }

    /** Sends the submit WhatsApp to one approver; NOTIFICATION-WHATSAPP gating is done by the caller. */
    private Uni<Void> whatsappOneApprover(io.vertx.mutiny.sqlclient.Pool pool, LeaveApplication e, String approver) {
        return staffRepo.findByStaffId(pool, approver).flatMap(approverStaff -> {
            String mobile = approverStaff != null ? approverStaff.getTelMobile() : null;
            if (mobile == null || mobile.isBlank()) {
                System.err.println("[WhatsApp] leave-submitted skipped for leave " + e.getUniqId()
                        + ": approver " + approver + " has no TelMobile");
                return Uni.createFrom().voidItem();
            }
            String who = (e.getStaffName() != null && !e.getStaffName().isBlank())
                    ? e.getStaffName() : e.getStaffId();
            String approverName = (approverStaff.getName() != null && !approverStaff.getName().isBlank())
                    ? approverStaff.getName() : approver;
            boolean cancel = ACTION_CANCEL.equals(e.getLeaveAction());
            String leaveType = cancel ? nz(e.getLeaveType()) + " (cancellation)" : nz(e.getLeaveType());
            System.err.println("[WhatsApp] leave-submitted sending to " + mobile + " for leave " + e.getUniqId());
            return whatsappNotificationService.sendLeaveSubmitted(mobile, approverName, who,
                    leaveType, periodText(e).trim())
                    .invoke(sent -> System.err.println("[WhatsApp] leave-submitted send "
                            + (sent ? "OK" : "FAILED") + " to " + mobile + " for leave " + e.getUniqId()))
                    .replaceWithVoid();
        }).onFailure().invoke(err -> System.err.println(
                "[WhatsApp] leave-submitted send failed for leave " + e.getUniqId() + ": " + err))
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
