package com.aisolutions.hrmanagement.service.approval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.hrmanagement.dto.ApprovalTrailDTO;
import com.aisolutions.hrmanagement.dto.ApprovalTrailStepDTO;
import com.aisolutions.hrmanagement.repository.ApprovalFlowRepository;
import com.aisolutions.hrmanagement.repository.ApprovalFlowRepository.Action;
import com.aisolutions.hrmanagement.repository.ApprovalFlowRepository.Tier;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;
import lombok.extern.slf4j.Slf4j;

/**
 * Applicant-side view of a claim's approval flow: freezes the live ON tiers onto a claim at submit,
 * reports the pending approvers to notify, and renders the applicant trail. A flow is active only
 * when the header is ON with at least one ON tier; otherwise the claim falls back to the
 * system-parameter approver. Pure sequencing rules are shared with {@link ApprovalFlowService}.
 */
@ApplicationScoped
@Slf4j
public class ClaimApprovalFlowService {

    public static final String MODULE_ID = "mod18";
    public static final String SCREEN_TYPE = "StaffClaim";

    private static final String TYPE_STAFF = "STAFF";
    private static final String TYPE_DEPARTMENT = "DEPARTMENT";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_PENDING = "PENDING";
    private static final String ACT_REJECT = "REJECT";
    private static final String ACT_RESUME = "RESUME";

    @Inject
    ApprovalFlowRepository flowRepo;

    /** The resolved flow for one claim: its frozen tiers and its action log. */
    public record ResolvedFlow(boolean active, String mode, List<Tier> tiers, List<Action> actions) {}

    /** What a submit needs to freeze a claim's flow: the claim, the submitter's department and the actor/time. */
    public record SubmitContext(long claimId, String submitterDept, String actor, LocalDateTime now) {}

    /** Resolves a claim's frozen snapshot tiers and action log (snapshot captured at submit). */
    public Uni<ResolvedFlow> resolve(SqlClient client, long claimId) {
        return flowRepo.findSnapshot(client, MODULE_ID, SCREEN_TYPE, claimId)
                .flatMap(snap -> flowRepo.findActions(client, MODULE_ID, SCREEN_TYPE, claimId)
                        .map(actions -> new ResolvedFlow(
                                !snap.tiers().isEmpty(), snap.mode(), snap.tiers(), effective(actions))));
    }

    /** The action log with RESUME markers applied: a REJECT before the latest RESUME no longer halts the flow. */
    public static List<Action> effective(List<Action> actions) {
        int lastResume = -1;
        for (int i = 0; i < actions.size(); i++) {
            if (ACT_RESUME.equalsIgnoreCase(actions.get(i).action())) {
                lastResume = i;
            }
        }
        List<Action> out = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            boolean marker = ACT_RESUME.equalsIgnoreCase(a.action());
            boolean reopened = ACT_REJECT.equalsIgnoreCase(a.action()) && i < lastResume;
            if (!marker && !reopened) {
                out.add(a);
            }
        }
        return out;
    }

    /** True when every frozen tier has approved the claim (an unfinished flow keeps it in review). */
    public static boolean isFlowComplete(ResolvedFlow flow) {
        return !flow.active() || ApprovalFlowService.isFullyApproved(flow.tiers(), flow.actions());
    }

    /** Logs a RESUME marker when a tier REJECT has halted the claim's flow, so that tier is pending again. */
    public Uni<Void> resumeIfReturned(SqlClient tx, long claimId, String actor, LocalDateTime now) {
        return resolve(tx, claimId)
                .flatMap(flow -> flow.active() && ApprovalFlowService.isRejected(flow.actions())
                        ? flowRepo.insertAction(tx, MODULE_ID, SCREEN_TYPE, claimId, null, ACT_RESUME, null, actor, now)
                        : Uni.createFrom().voidItem());
    }

    /** Whether an active multi-tier flow governs claims (header ON with at least one ON tier). */
    public Uni<Boolean> isFlowActive(SqlClient pool) {
        return flowRepo.findHeader(pool, MODULE_ID, SCREEN_TYPE).flatMap(header -> {
            boolean on = header != null && "ON".equalsIgnoreCase(header.status());
            return on
                    ? flowRepo.findOnTiers(pool, header.refId()).map(tiers -> !tiers.isEmpty())
                    : Uni.createFrom().item(false);
        });
    }

    /**
     * Freezes the live ON tiers onto a claim at submit; DEPARTMENT tiers resolve to the submitter
     * department's in-charge and PROJECT tiers are dropped. Runs on the submit transaction.
     */
    public Uni<Void> snapshotAtSubmit(SqlClient tx, SubmitContext ctx) {
        return flowRepo.findHeader(tx, MODULE_ID, SCREEN_TYPE).flatMap(header -> {
            boolean on = header != null && "ON".equalsIgnoreCase(header.status());
            if (!on) {
                return Uni.createFrom().voidItem();
            }
            return flowRepo.findOnTiers(tx, header.refId())
                    .flatMap(tiers -> resolveTiersForSnapshot(tx, tiers, ctx.submitterDept()))
                    .flatMap(resolved -> resolved.isEmpty()
                            ? Uni.createFrom().voidItem()
                            : flowRepo.insertSnapshot(
                                    tx,
                                    MODULE_ID,
                                    SCREEN_TYPE,
                                    ctx.claimId(),
                                    header.mode(),
                                    resolved,
                                    ctx.actor(),
                                    ctx.now()));
        });
    }

    /** STAFF keeps its approver; DEPARTMENT becomes the submitter department's in-charge; others are dropped. */
    private Uni<List<Tier>> resolveTiersForSnapshot(SqlClient client, List<Tier> tiers, String submitterDept) {
        boolean needsDept = tiers.stream().anyMatch(t -> TYPE_DEPARTMENT.equalsIgnoreCase(t.approverType()));
        Uni<String> inChargeUni = needsDept
                ? flowRepo.findDepartmentInCharge(client, submitterDept)
                : Uni.createFrom().nullItem();
        return inChargeUni.map(deptInCharge -> {
            List<Tier> resolved = new ArrayList<>();
            for (Tier t : tiers) {
                String type =
                        t.approverType() == null ? TYPE_STAFF : t.approverType().toUpperCase();
                String approver =
                        TYPE_DEPARTMENT.equals(type) ? deptInCharge : TYPE_STAFF.equals(type) ? t.staffId() : null;
                if (approver == null || approver.isBlank()) {
                    continue;
                }
                resolved.add(new Tier(t.level(), TYPE_STAFF, approver, null, null, "ON"));
            }
            return resolved;
        });
    }

    /** Staff ids to notify from the frozen snapshot: the pending tier(s); empty when no flow governs the claim. */
    public Uni<List<String>> pendingApproversFromSnapshot(SqlClient pool, long claimId) {
        return resolve(pool, claimId)
                .map(flow -> flow.active()
                        ? ApprovalFlowService.pendingApproverStaffIds(flow.tiers(), flow.actions(), flow.mode())
                        : List.of());
    }

    /**
     * Builds the applicant approval trail for a claim: one step per frozen tier when a flow governs
     * it, otherwise the bare action log. A claim with no snapshot and no actions has no trail.
     */
    public Uni<ApprovalTrailDTO> buildTrail(SqlClient pool, long claimId, String claimStatus) {
        return resolve(pool, claimId).map(flow -> {
            boolean hasActions = !flow.actions().isEmpty();
            boolean pending = STATUS_PENDING.equalsIgnoreCase(claimStatus)
                    || "SUBMITTED".equalsIgnoreCase(claimStatus)
                    || "PARTIALLY-APPROVED".equalsIgnoreCase(claimStatus);
            boolean governed = flow.active() && (hasActions || pending);
            ApprovalTrailDTO d = new ApprovalTrailDTO();
            d.setMyTurn(false); // applicant view — never an approver
            if (governed) {
                int approved = 0;
                for (Tier t : flow.tiers()) {
                    if (ApprovalFlowService.isLevelApproved(flow.actions(), t.level())) {
                        approved++;
                    }
                }
                d.setFlowActive(true);
                d.setMode(flow.mode());
                d.setSteps(tierSteps(flow));
                d.setApprovedCount(approved);
                d.setTotalTiers(flow.tiers().size());
            } else {
                d.setFlowActive(false);
                d.setSteps(hasActions ? actionSteps(flow.actions()) : List.of());
                d.setApprovedCount(0);
                d.setTotalTiers(0);
            }
            return d;
        });
    }

    private List<ApprovalTrailStepDTO> tierSteps(ResolvedFlow flow) {
        boolean rejected = ApprovalFlowService.isRejected(flow.actions());
        List<ApprovalTrailStepDTO> steps = new ArrayList<>();
        for (Tier t : flow.tiers()) {
            Action act = lastActionForLevel(flow.actions(), t.level());
            if (act != null) {
                boolean rej = ACT_REJECT.equalsIgnoreCase(act.action());
                steps.add(new ApprovalTrailStepDTO(
                        t.level(),
                        act.actionStaff(),
                        coalesce(act.actionStaffName(), t.staffName(), act.actionStaff()),
                        coalesce(act.actionDept(), t.dept()),
                        rej ? STATUS_REJECTED : STATUS_APPROVED,
                        act.reason(),
                        act.actionDate()));
            } else if (!rejected) {
                steps.add(new ApprovalTrailStepDTO(
                        t.level(),
                        t.staffId(),
                        coalesce(t.staffName(), t.staffId()),
                        t.dept(),
                        STATUS_PENDING,
                        null,
                        null));
            }
        }
        return steps;
    }

    private List<ApprovalTrailStepDTO> actionSteps(List<Action> actions) {
        List<ApprovalTrailStepDTO> steps = new ArrayList<>();
        for (Action a : actions) {
            boolean rej = ACT_REJECT.equalsIgnoreCase(a.action());
            steps.add(new ApprovalTrailStepDTO(
                    a.level(),
                    a.actionStaff(),
                    coalesce(a.actionStaffName(), a.actionStaff()),
                    a.actionDept(),
                    rej ? STATUS_REJECTED : STATUS_APPROVED,
                    a.reason(),
                    a.actionDate()));
        }
        return steps;
    }

    private static Action lastActionForLevel(List<Action> actions, int level) {
        Action found = null;
        for (Action a : actions) {
            if (a.level() != null && a.level() == level) {
                found = a;
            }
        }
        return found;
    }

    private static String coalesce(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
