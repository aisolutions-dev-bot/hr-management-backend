package com.aisolutions.hrmanagement.service.notification;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.hrmanagement.dto.StaffClaimDTO;
import com.aisolutions.hrmanagement.entity.Staff;
import com.aisolutions.hrmanagement.entity.StaffClaim;
import com.aisolutions.hrmanagement.repository.StaffRepository;
import com.aisolutions.hrmanagement.service.SystemParameterService;
import com.aisolutions.shared.notification.NotificationTransaction;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

/**
 * Builds and stages registry parameters for submitted, resubmitted and approved staff claims.
 * Delegates channel switches and staging to
 * {@link HrNotificationChannelDispatcher}, parameter and staff reads to
 * {@link SystemParameterService} and {@link StaffRepository}, and content rendering to the
 * {@link StaffClaimNotificationContentRenderer}.
 */
@ApplicationScoped
public class StaffClaimNotificationNotifier {

    private static final Logger LOG = Logger.getLogger(StaffClaimNotificationNotifier.class);

    private static final String ALL_NOTIFICATION_CHANNELS = "all";

    @Inject
    HrNotificationChannelDispatcher notificationChannelDispatcher;

    @Inject
    StaffRepository staffRepository;

    @Inject
    SystemParameterService systemParameterService;

    /** Stages the claim-submitted or claim-resubmitted content for each approver in {@code recipientStaffIds}. */
    public Uni<Void> notifyClaimSubmitted(
            NotificationTransaction context, StaffClaimDTO claim, String claimAction, List<String> recipientStaffIds) {
        if (recipientStaffIds == null || recipientStaffIds.isEmpty()) {
            return recordNotificationSkip(
                    context, ALL_NOTIFICATION_CHANNELS, claim.getUniqId(), "approver_identifier_missing");
        }
        return notificationChannelDispatcher
                .loadChannelToggles(context.transaction())
                .flatMap(toggles -> queueClaimSubmittedChannels(
                        new ClaimSubmittedBatch(context, claim, claimAction, recipientStaffIds, toggles)));
    }

    /** Stages the "accepted the rejection and continued" content for each approver the claim is back with. */
    public Uni<Void> notifyClaimContinued(
            NotificationTransaction context, StaffClaimDTO claim, List<String> recipientStaffIds) {
        return notifyClaimSubmitted(
                context, claim, StaffClaimNotificationContentRenderer.ACTION_CONTINUED, recipientStaffIds);
    }

    /** Stages the claim-approved decision content for each claimant-facing recipient in {@code claimantStaffIds}. */
    public Uni<Void> notifyClaimApproved(
            NotificationTransaction context, StaffClaim claim, List<String> claimantStaffIds) {
        if (claimantStaffIds == null || claimantStaffIds.isEmpty()) {
            return recordNotificationSkip(
                    context, ALL_NOTIFICATION_CHANNELS, claim.getUniqId(), "claimant_identifier_missing");
        }
        return notificationChannelDispatcher
                .loadChannelToggles(context.transaction())
                .flatMap(toggles -> queueClaimApprovedChannels(context, claim, claimantStaffIds, toggles));
    }

    /** Audits disabled channels or loads currency before staging every pending approver. */
    private Uni<Void> queueClaimSubmittedChannels(ClaimSubmittedBatch batch) {
        if (!batch.toggles().anyEnabled()) {
            return recordNotificationSkip(
                    batch.context(), "email,sms,whatsapp", batch.claim().getUniqId(), "channels_disabled");
        }
        return loadBaseCurrency(batch.context())
                .flatMap(baseCurrency -> stageSubmittedClaimForEveryApprover(batch, baseCurrency));
    }

    /** Stages the submitted-claim content for every approver of the batch in turn. */
    private Uni<Void> stageSubmittedClaimForEveryApprover(ClaimSubmittedBatch batch, String baseCurrency) {
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (String recipientStaffId : batch.recipientStaffIds()) {
            ClaimSubmittedDispatch dispatch = new ClaimSubmittedDispatch(
                    batch.context(), batch.claim(), batch.claimAction(), recipientStaffId, batch.toggles());
            chain = chain.flatMap(v -> loadSubmittedClaimApprover(dispatch, baseCurrency));
        }
        return chain;
    }

    /** Reads the approver and delegates content preparation to {@link #stageSubmittedClaimForApprover}. */
    private Uni<Void> loadSubmittedClaimApprover(ClaimSubmittedDispatch dispatch, String baseCurrency) {
        return staffRepository
                .findByStaffId(dispatch.context().transaction(), dispatch.recipientStaffId())
                .flatMap(approverStaff -> stageSubmittedClaimForApprover(dispatch, approverStaff, baseCurrency));
    }

    /** Audits missing staff or resolves the submitter before {@link #stageClaimSubmittedContent}. */
    private Uni<Void> stageSubmittedClaimForApprover(
            ClaimSubmittedDispatch dispatch, Staff approverStaff, String baseCurrency) {
        if (approverStaff == null) {
            return recordNotificationSkip(
                    dispatch.context(),
                    ALL_NOTIFICATION_CHANNELS,
                    dispatch.claim().getUniqId(),
                    "staff_missing");
        }
        return resolveStaffDisplayName(dispatch.context(), resolveSubmitterStaffId(dispatch.claim()))
                .flatMap(submitterName ->
                        stageClaimSubmittedContent(dispatch, approverStaff, submitterName, baseCurrency));
    }

    /** Renders the claim content, then delegates every channel to the dispatcher. */
    private Uni<Void> stageClaimSubmittedContent(
            ClaimSubmittedDispatch dispatch, Staff approverStaff, String submitterName, String baseCurrency) {
        HrNotificationChannelContent content = StaffClaimNotificationContentRenderer.buildClaimSubmittedContent(
                new StaffClaimNotificationContentRenderer.ClaimSubmittedRenderRequest(
                        dispatch.claim(),
                        dispatch.claimAction(),
                        approverStaff,
                        dispatch.recipientStaffId(),
                        submitterName,
                        baseCurrency));
        return notificationChannelDispatcher.stageStaffChannels(
                new HrNotificationChannelDispatcher.StaffChannelDispatch(
                        dispatch.context(),
                        dispatch.toggles(),
                        approverStaff,
                        content,
                        dispatch.claim().getUniqId()));
    }

    /** Audits disabled channels or loads currency before staging every claimant-facing recipient. */
    private Uni<Void> queueClaimApprovedChannels(
            NotificationTransaction context,
            StaffClaim claim,
            List<String> claimantStaffIds,
            HrNotificationChannelToggles toggles) {
        if (!toggles.anyEnabled()) {
            return recordNotificationSkip(context, "email,sms,whatsapp", claim.getUniqId(), "channels_disabled");
        }
        ClaimApprovedBatch batch = new ClaimApprovedBatch(context, claim, claimantStaffIds, toggles);
        return loadBaseCurrency(context)
                .flatMap(baseCurrency -> stageApprovedClaimForEveryRecipient(batch, baseCurrency));
    }

    /** Stages the approved-claim content for every claimant-facing recipient of the batch in turn. */
    private Uni<Void> stageApprovedClaimForEveryRecipient(ClaimApprovedBatch batch, String baseCurrency) {
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (String claimantStaffId : batch.claimantStaffIds()) {
            ClaimApprovedDispatch dispatch =
                    new ClaimApprovedDispatch(batch.context(), batch.claim(), claimantStaffId, batch.toggles());
            chain = chain.flatMap(v -> loadApprovedClaimant(dispatch, baseCurrency));
        }
        return chain;
    }

    /** Reads claimant staff and delegates rendering to {@link #stageApprovedClaimContent}. */
    private Uni<Void> loadApprovedClaimant(ClaimApprovedDispatch dispatch, String baseCurrency) {
        return staffRepository
                .findByStaffId(dispatch.context().transaction(), dispatch.claimantStaffId())
                .flatMap(claimantStaff -> stageApprovedClaimContent(dispatch, claimantStaff, baseCurrency));
    }

    /** Audits missing staff or renders content before delegating to the shared channel dispatcher. */
    private Uni<Void> stageApprovedClaimContent(
            ClaimApprovedDispatch dispatch, Staff claimantStaff, String baseCurrency) {
        if (claimantStaff == null) {
            return recordNotificationSkip(
                    dispatch.context(),
                    ALL_NOTIFICATION_CHANNELS,
                    dispatch.claim().getUniqId(),
                    "staff_missing");
        }
        HrNotificationChannelContent content = StaffClaimNotificationContentRenderer.buildClaimApprovedContent(
                dispatch.claim(), claimantStaff, dispatch.claimantStaffId(), baseCurrency);
        return notificationChannelDispatcher.stageStaffChannels(
                new HrNotificationChannelDispatcher.StaffChannelDispatch(
                        dispatch.context(),
                        dispatch.toggles(),
                        claimantStaff,
                        content,
                        dispatch.claim().getUniqId()));
    }

    /** Carries one approved claim and its channel switches through recipient resolution. */
    private record ClaimApprovedDispatch(
            NotificationTransaction context,
            StaffClaim claim,
            String claimantStaffId,
            HrNotificationChannelToggles toggles) {}

    /** Reads the base currency on the business transaction; read failures abort notification staging. */
    private Uni<String> loadBaseCurrency(NotificationTransaction context) {
        return systemParameterService.loadParameter(context.transaction(), SystemParameterService.PARAM_BASE_CURRENCY);
    }

    /** Resolves a staff display name on the transaction; read failures abort notification staging. */
    private Uni<String> resolveStaffDisplayName(NotificationTransaction context, String staffId) {
        return staffRepository.findNameByStaffId(context.transaction(), staffId);
    }

    /** The submitting staff id: the recorded entry staff, else the owning staff. */
    private String resolveSubmitterStaffId(StaffClaimDTO claim) {
        return claim.getEntryStaff() != null ? claim.getEntryStaff() : claim.getStaffId();
    }

    /** Records an intentional skip without recipient contact information. */
    private Uni<Void> recordNotificationSkip(
            NotificationTransaction context, String channel, Object businessIdentity, String reason) {
        LOG.warnf(
                "notification_skipped company_id=%s channel=%s business_identity=%s reason=%s",
                context.companyId(), channel, businessIdentity, reason);
        return Uni.createFrom().voidItem();
    }

    /** One submitted or resubmitted claim with every approver to stage and the channel switches. */
    private record ClaimSubmittedBatch(
            NotificationTransaction context,
            StaffClaimDTO claim,
            String claimAction,
            List<String> recipientStaffIds,
            HrNotificationChannelToggles toggles) {}

    /** One approved claim with every claimant-facing recipient to stage and the channel switches. */
    private record ClaimApprovedBatch(
            NotificationTransaction context,
            StaffClaim claim,
            List<String> claimantStaffIds,
            HrNotificationChannelToggles toggles) {}

    /** The claim context carried from the approver parameter read into content staging. */
    private record ClaimSubmittedDispatch(
            NotificationTransaction context,
            StaffClaimDTO claim,
            String claimAction,
            String recipientStaffId,
            HrNotificationChannelToggles toggles) {}
}
