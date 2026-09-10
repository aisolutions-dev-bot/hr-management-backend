package com.aisolutions.hrmanagement.service.approval;

import com.aisolutions.hrmanagement.dto.ApprovalTrailDTO;
import com.aisolutions.hrmanagement.dto.ApprovalTrailStepDTO;
import com.aisolutions.hrmanagement.entity.LeaveApplication;
import com.aisolutions.hrmanagement.repository.ApprovalFlowRepository;
import com.aisolutions.hrmanagement.repository.ApprovalFlowRepository.Action;
import com.aisolutions.hrmanagement.repository.ApprovalFlowRepository.Tier;
import com.aisolutions.hrmanagement.repository.StaffRepository;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the leave approval flow and reads its action log (read-only on the HRMS side:
 * routes the submit notification to the first pending approver(s) and renders the trail).
 * A flow is "active" only when the header is ON with at least one ON tier; otherwise the
 * leave falls back to the applicant-chosen approver.
 */
@ApplicationScoped
public class ApprovalFlowService {

    public static final String MODULE_ID   = "mod18";
    public static final String SCREEN_TYPE = "LeaveApplication";

    private static final String MODE_SEQUENTIAL = "SEQUENTIAL";
    private static final String ACT_APPROVE = "APPROVE";
    private static final String ACT_REJECT  = "REJECT";

    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_PENDING  = "PENDING";

    @Inject ApprovalFlowRepository flowRepo;
    @Inject StaffRepository staffRepo;

    /** The resolved flow for one leave: its ON tiers and its action log. */
    public record ResolvedFlow(boolean active, String mode, List<Tier> tiers, List<Action> actions) {}

    /**
     * Resolves the flow and action log for one leave from its frozen SNAPSHOT (captured
     * at submit) — NOT the live config, so later flow edits never affect an in-flight
     * leave. {@code active} is true when the leave has snapshot tiers; a leave with no
     * snapshot is not flow-governed (single applicant-chosen approver fallback). Actions
     * are always loaded so a fallback/legacy trail can still be rendered.
     */
    public Uni<ResolvedFlow> resolve(SqlClient pool, long leaveId) {
        return flowRepo.findSnapshot(pool, MODULE_ID, SCREEN_TYPE, leaveId).flatMap(snap ->
            flowRepo.findActions(pool, MODULE_ID, SCREEN_TYPE, leaveId)
                .map(actions -> new ResolvedFlow(!snap.tiers().isEmpty(), snap.mode(), snap.tiers(), actions)));
    }

    /**
     * Freezes the current live ON tiers onto a document at submit time (one snapshot row
     * per tier). No-op when no flow is active. Runs on the submit transaction so the
     * snapshot commits atomically with the leave.
     */
    public Uni<Void> snapshotAtSubmit(SqlClient tx, long leaveId, String actor, LocalDateTime now) {
        return flowRepo.findHeader(tx, MODULE_ID, SCREEN_TYPE).flatMap(header -> {
            boolean on = header != null && "ON".equalsIgnoreCase(header.status());
            if (!on) {
                return Uni.createFrom().voidItem();
            }
            return flowRepo.findOnTiers(tx, header.refId()).flatMap(tiers ->
                tiers.isEmpty()
                    ? Uni.createFrom().voidItem()
                    : flowRepo.insertSnapshot(tx, MODULE_ID, SCREEN_TYPE, leaveId, header.mode(), tiers, actor, now));
        });
    }

    /** Whether an active multi-tier flow governs leave (header ON with at least one ON tier). */
    public Uni<Boolean> isFlowActive(SqlClient pool) {
        return flowRepo.findHeader(pool, MODULE_ID, SCREEN_TYPE).flatMap(header -> {
            boolean on = header != null && "ON".equalsIgnoreCase(header.status());
            if (!on) {
                return Uni.createFrom().item(false);
            }
            return flowRepo.findOnTiers(pool, header.refId()).map(tiers -> !tiers.isEmpty());
        });
    }

    /** The configured ON tier chain (level + approver name + dept) for the apply screen. */
    public record FlowChain(boolean active, String mode, List<Tier> tiers) {}

    /**
     * The flow chain to show the applicant on the apply form: the ordered ON tiers
     * and their approvers when a flow is active, else an inactive chain (the
     * applicant then picks their own approver).
     */
    public Uni<FlowChain> flowChain(SqlClient pool) {
        return flowRepo.findHeader(pool, MODULE_ID, SCREEN_TYPE).flatMap(header -> {
            boolean on = header != null && "ON".equalsIgnoreCase(header.status());
            if (!on) {
                return Uni.createFrom().item(
                        new FlowChain(false, header != null ? header.mode() : null, List.of()));
            }
            return flowRepo.findOnTiers(pool, header.refId())
                    .map(tiers -> new FlowChain(!tiers.isEmpty(), header.mode(), tiers));
        });
    }

    /**
     * The staff ids that must be notified when a leave is first submitted under a
     * flow: the first pending tier (SEQUENTIAL) or every ON tier (PARALLEL).
     * Empty when there is no active flow — the caller then falls back to the
     * applicant-chosen approver.
     */
    public Uni<List<String>> initialPendingApprovers(SqlClient pool) {
        return flowRepo.findHeader(pool, MODULE_ID, SCREEN_TYPE).flatMap(header -> {
            boolean on = header != null && "ON".equalsIgnoreCase(header.status());
            if (!on) {
                return Uni.createFrom().item(List.of());
            }
            return flowRepo.findOnTiers(pool, header.refId())
                    .map(tiers -> pendingApproverStaffIds(tiers, List.of(), header.mode()));
        });
    }

    // ─────────────────────────────────────────────────────────
    //  PURE FLOW COMPUTE (shared rules)
    // ─────────────────────────────────────────────────────────

    public static boolean isRejected(List<Action> actions) {
        return actions.stream().anyMatch(a -> ACT_REJECT.equalsIgnoreCase(a.action()));
    }

    public static boolean isLevelApproved(List<Action> actions, int level) {
        return actions.stream().anyMatch(a -> ACT_APPROVE.equalsIgnoreCase(a.action())
                && a.level() != null && a.level() == level);
    }

    /** True when every ON tier has been approved (the whole flow is complete). */
    public static boolean isFullyApproved(List<Tier> tiers, List<Action> actions) {
        return !tiers.isEmpty() && tiers.stream().allMatch(t -> isLevelApproved(actions, t.level()));
    }

    /**
     * The tier levels awaiting a decision: the first un-approved tier (SEQUENTIAL)
     * or all un-approved tiers (PARALLEL). Empty when rejected or fully approved.
     */
    public static List<Integer> pendingLevels(List<Tier> tiers, List<Action> actions, String mode) {
        if (isRejected(actions)) return List.of();
        List<Integer> pending = new ArrayList<>();
        for (Tier t : tiers) {
            if (!isLevelApproved(actions, t.level())) pending.add(t.level());
        }
        if (pending.isEmpty()) return List.of();
        if (MODE_SEQUENTIAL.equalsIgnoreCase(mode)) return List.of(pending.get(0));
        return pending;
    }

    /** The tier level {@code actor} is currently responsible for, or null if not their turn. */
    public static Integer pendingLevelForActor(List<Tier> tiers, List<Action> actions,
                                               String mode, String actor) {
        if (actor == null) return null;
        List<Integer> levels = pendingLevels(tiers, actions, mode);
        for (Tier t : tiers) {
            if (levels.contains(t.level()) && actor.equalsIgnoreCase(t.staffId())) {
                return t.level();
            }
        }
        return null;
    }

    /** Distinct staff ids of the currently pending tiers. */
    public static List<String> pendingApproverStaffIds(List<Tier> tiers, List<Action> actions, String mode) {
        List<Integer> levels = pendingLevels(tiers, actions, mode);
        List<String> ids = new ArrayList<>();
        for (Tier t : tiers) {
            if (levels.contains(t.level()) && !ids.contains(t.staffId())) ids.add(t.staffId());
        }
        return ids;
    }

    // ─────────────────────────────────────────────────────────
    //  TRAIL
    // ─────────────────────────────────────────────────────────

    /**
     * Builds the approval trail for a leave. When a flow is active the steps are
     * one-per-ON-tier; otherwise it reflects the fallback action log, and for
     * pre-flow legacy records the leave's own decision fields.
     */
    public Uni<ApprovalTrailDTO> buildTrail(SqlClient pool, LeaveApplication leave) {
        return resolve(pool, leave.getUniqId()).flatMap(flow -> {
            boolean hasActions = !flow.actions().isEmpty();
            boolean pending = STATUS_PENDING.equalsIgnoreCase(leave.getStatus());
            // The flow only governs leaves that actually entered it: ones with logged actions, or ones
            // still pending under an active flow. A leave decided before the flow existed (terminally
            // decided, no actions) keeps its own historical trail — the flow is not applied retroactively.
            boolean governed = flow.active() && (hasActions || pending);

            int totalTiers = governed ? flow.tiers().size() : 0;
            int approvedCount = 0;
            if (governed) {
                for (Tier t : flow.tiers()) {
                    if (isLevelApproved(flow.actions(), t.level())) approvedCount++;
                }
            }

            if (governed) {
                return Uni.createFrom().item(dto(true, flow.mode(), tierSteps(flow), approvedCount, totalTiers));
            }
            if (hasActions) {
                return Uni.createFrom().item(dto(false, null, actionSteps(flow.actions()), 0, 0));
            }
            return legacySteps(pool, leave).map(steps -> dto(false, null, steps, 0, 0));
        });
    }

    private static ApprovalTrailDTO dto(boolean active, String mode, List<ApprovalTrailStepDTO> steps,
                                        int approvedCount, int totalTiers) {
        ApprovalTrailDTO d = new ApprovalTrailDTO();
        d.setFlowActive(active);
        d.setMode(mode);
        d.setSteps(steps);
        d.setMyTurn(false);   // applicant view — never an approver
        d.setApprovedCount(approvedCount);
        d.setTotalTiers(totalTiers);
        return d;
    }

    /** One step per ON tier, using the last action logged for that level. */
    private List<ApprovalTrailStepDTO> tierSteps(ResolvedFlow flow) {
        boolean rejected = isRejected(flow.actions());
        List<ApprovalTrailStepDTO> steps = new ArrayList<>();
        for (Tier t : flow.tiers()) {
            Action act = lastActionForLevel(flow.actions(), t.level());
            if (act != null) {
                boolean rej = ACT_REJECT.equalsIgnoreCase(act.action());
                steps.add(new ApprovalTrailStepDTO(t.level(), act.actionStaff(),
                        coalesce(act.actionStaffName(), t.staffName(), act.actionStaff()),
                        coalesce(act.actionDept(), t.dept()),
                        rej ? STATUS_REJECTED : STATUS_APPROVED, act.reason(), act.actionDate()));
            } else if (!rejected) {
                // Once the flow is rejected, later un-acted tiers are moot — omit them.
                steps.add(new ApprovalTrailStepDTO(t.level(), t.staffId(),
                        coalesce(t.staffName(), t.staffId()), t.dept(), STATUS_PENDING, null, null));
            }
        }
        return steps;
    }

    /** Steps straight from a fallback action log (no configured tiers). */
    private List<ApprovalTrailStepDTO> actionSteps(List<Action> actions) {
        List<ApprovalTrailStepDTO> steps = new ArrayList<>();
        for (Action a : actions) {
            boolean rejected = ACT_REJECT.equalsIgnoreCase(a.action());
            steps.add(new ApprovalTrailStepDTO(a.level(), a.actionStaff(),
                    coalesce(a.actionStaffName(), a.actionStaff()), a.actionDept(),
                    rejected ? STATUS_REJECTED : STATUS_APPROVED, a.reason(), a.actionDate()));
        }
        return steps;
    }

    /** A single step derived from a pre-flow leave's own decision fields. */
    private Uni<List<ApprovalTrailStepDTO>> legacySteps(SqlClient pool, LeaveApplication leave) {
        boolean decided = leave.getApprovedBy() != null && !leave.getApprovedBy().isBlank();
        String who = decided ? leave.getApprovedBy() : leave.getApproverStaffId();
        if (who == null || who.isBlank()) {
            return Uni.createFrom().item(List.of());
        }
        String status = switch (nz(leave.getStatus()).toUpperCase()) {
            case STATUS_REJECTED -> STATUS_REJECTED;
            case STATUS_PENDING, "" -> STATUS_PENDING;
            default -> STATUS_APPROVED;   // APPROVED / CANCELLED both had an approval
        };
        return staffRepo.findNameByStaffId(pool, who).onFailure().recoverWithItem((String) null)
            .map(name -> List.of(new ApprovalTrailStepDTO(null, who, coalesce(name, who), null,
                    status, leave.getRejectReason(),
                    STATUS_PENDING.equals(status) ? null : leave.getApprovedDate())));
    }

    private static Action lastActionForLevel(List<Action> actions, int level) {
        Action found = null;
        for (Action a : actions) {
            if (a.level() != null && a.level() == level) found = a;   // keep the latest
        }
        return found;
    }

    private static String coalesce(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
