package com.aisolutions.hrmanagement.service.notification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.hrmanagement.dto.StaffClaimDTO;
import com.aisolutions.hrmanagement.entity.Staff;
import com.aisolutions.hrmanagement.entity.StaffClaim;
import com.aisolutions.hrmanagement.repository.StaffRepository;
import com.aisolutions.hrmanagement.service.SystemParameterService;
import com.aisolutions.hrmanagement.service.staffclaim.ClaimEmailTemplate;
import com.aisolutions.shared.notification.NotificationTransaction;
import io.smallrye.mutiny.Uni;

/**
 * Builds and stages the staff-claim submitted, resubmitted and approved email, SMS and WhatsApp
 * content on the shared notification outbox. Delegates channel switches and staging to
 * {@link HrNotificationChannelDispatcher}, parameter and staff reads to
 * {@link SystemParameterService} and {@link StaffRepository}, and content rendering to the
 * private {@code renderClaim...} helpers.
 */
@ApplicationScoped
public class StaffClaimNotificationNotifier {

    /** m07SystemParameters key naming the staff who receives claim-submitted notifications. */
    public static final String PARAM_HR_APPROVER = "HR-ADMIN-APPRV-IN-CHARGE";

    private static final String CLAIM_ACTION_RESUBMITTED = "resubmitted";
    private static final String CLAIM_OUTCOME_APPROVED = "approved";
    private static final String SUBJECT_SEPARATOR = " - ";
    private static final String SMS_BRAND_SUFFIX = ". - AI Solutions";
    private static final String WHATSAPP_BLANK_PLACEHOLDER = "-";
    private static final String CLAIM_SUBMITTED_TEMPLATE_NAME = "hr_claim_submitted_v1";
    private static final String CLAIM_DECISION_TEMPLATE_NAME = "hr_claim_decision_v1";

    @Inject
    HrNotificationChannelDispatcher notificationChannelDispatcher;

    @Inject
    StaffRepository staffRepository;

    @Inject
    SystemParameterService systemParameterService;

    /**
     * Stages the claim-submitted or claim-resubmitted content for the configured HR approver.
     * Delegates parameter resolution to {@link SystemParameterService}, tenant switches to
     * {@link HrNotificationChannelDispatcher#loadChannelToggles} and staging to
     * {@link #queueClaimSubmittedChannels}.
     */
    public Uni<Void> notifyClaimSubmitted(NotificationTransaction context, StaffClaimDTO claim, String claimAction) {
        return systemParameterService
                .loadParameter(context.transaction(), PARAM_HR_APPROVER)
                .onFailure()
                .recoverWithItem((String) null)
                .flatMap(recipientStaffId -> {
                    if (isAbsent(recipientStaffId)) {
                        return Uni.createFrom().voidItem();
                    }
                    return notificationChannelDispatcher
                            .loadChannelToggles(context.transaction())
                            .flatMap(toggles -> queueClaimSubmittedChannels(new ClaimSubmittedDispatch(
                                    context, claim, claimAction, recipientStaffId, toggles)));
                });
    }

    /**
     * Stages the claim-approved decision content for the claimant. Delegates tenant switches to
     * {@link HrNotificationChannelDispatcher#loadChannelToggles} and staging to
     * {@link #queueClaimApprovedChannels}.
     */
    public Uni<Void> notifyClaimApproved(NotificationTransaction context, StaffClaim claim) {
        String claimantStaffId = resolveClaimantStaffId(claim);
        if (isAbsent(claimantStaffId)) {
            return Uni.createFrom().voidItem();
        }
        return notificationChannelDispatcher
                .loadChannelToggles(context.transaction())
                .flatMap(toggles -> queueClaimApprovedChannels(context, claim, claimantStaffId, toggles));
    }

    /** Resolves the approver, submitter name and base currency, then stages claim content. */
    private Uni<Void> queueClaimSubmittedChannels(ClaimSubmittedDispatch dispatch) {
        if (!dispatch.toggles().anyEnabled()) {
            return Uni.createFrom().voidItem();
        }
        String submitterStaffId = resolveSubmitterStaffId(dispatch.claim());
        return loadBaseCurrency(dispatch.context())
                .flatMap(baseCurrency -> staffRepository
                        .findByStaffId(dispatch.context().transaction(), dispatch.recipientStaffId())
                        .onFailure()
                        .recoverWithItem((Staff) null)
                        .flatMap(approverStaff -> {
                            if (approverStaff == null) {
                                return Uni.createFrom().voidItem();
                            }
                            return resolveStaffDisplayName(dispatch.context(), submitterStaffId)
                                    .flatMap(submitterName -> stageClaimSubmittedContent(
                                            dispatch, approverStaff, submitterName, baseCurrency));
                        }));
    }

    /** Renders the claim content, then delegates every channel to the dispatcher. */
    private Uni<Void> stageClaimSubmittedContent(
            ClaimSubmittedDispatch dispatch, Staff approverStaff, String submitterName, String baseCurrency) {
        ClaimSubmittedNotificationContent content = renderClaimSubmittedContent(new ClaimSubmittedRenderRequest(
                dispatch.claim(),
                dispatch.claimAction(),
                approverStaff,
                dispatch.recipientStaffId(),
                submitterName,
                baseCurrency));
        return notificationChannelDispatcher.stageStaffChannels(
                dispatch.context(), dispatch.toggles(), approverStaff, content);
    }

    /** Resolves the claimant and base currency, then stages the approved decision content. */
    private Uni<Void> queueClaimApprovedChannels(
            NotificationTransaction context,
            StaffClaim claim,
            String claimantStaffId,
            HrNotificationChannelToggles toggles) {
        if (!toggles.anyEnabled()) {
            return Uni.createFrom().voidItem();
        }
        return loadBaseCurrency(context)
                .flatMap(baseCurrency -> staffRepository
                        .findByStaffId(context.transaction(), claimantStaffId)
                        .onFailure()
                        .recoverWithItem((Staff) null)
                        .flatMap(claimantStaff -> {
                            if (claimantStaff == null) {
                                return Uni.createFrom().voidItem();
                            }
                            ClaimApprovedNotificationContent content =
                                    renderClaimApprovedContent(claim, claimantStaff, claimantStaffId, baseCurrency);
                            return notificationChannelDispatcher.stageStaffChannels(
                                    context, toggles, claimantStaff, content);
                        }));
    }

    /** Renders the claim-submitted or claim-resubmitted content for all three channels. */
    private ClaimSubmittedNotificationContent renderClaimSubmittedContent(ClaimSubmittedRenderRequest request) {
        boolean resubmitted = CLAIM_ACTION_RESUBMITTED.equals(request.claimAction());
        ClaimSubmissionSummary summary = new ClaimSubmissionSummary(
                displayName(request.submitterName(), resolveSubmitterStaffId(request.claim())),
                nz(request.claim().getClaimPeriod()),
                money(request.baseCurrency(), request.claim().getClaimAmount()),
                resubmitted);
        String approverName = displayName(request.approverStaff().getName(), request.recipientStaffId());
        return new ClaimSubmittedNotificationContent(
                claimSubmittedSubject(summary),
                claimSubmittedEmailBody(approverName, summary),
                claimSubmittedSmsText(summary),
                CLAIM_SUBMITTED_TEMPLATE_NAME,
                claimSubmittedWhatsappTemplateComponents(approverName, summary, request.claimAction()));
    }

    /** Builds the claim-submitted or claim-resubmitted subject line. */
    private String claimSubmittedSubject(ClaimSubmissionSummary summary) {
        String prefix = summary.resubmitted() ? "Receipt resubmitted for review by " : "New staff claim submitted by ";
        return prefix + summary.claimantName() + SUBJECT_SEPARATOR + summary.claimPeriod();
    }

    /** Renders the claim-submitted or claim-resubmitted email body through the existing template. */
    private String claimSubmittedEmailBody(String approverName, ClaimSubmissionSummary summary) {
        if (summary.resubmitted()) {
            return ClaimEmailTemplate.buildResubmittedEmail(
                    approverName, summary.claimantName(), summary.claimPeriod());
        }
        return ClaimEmailTemplate.buildSubmittedEmail(
                approverName, summary.claimantName(), summary.claimPeriod(), summary.amount());
    }

    /** Builds the claim-submitted or claim-resubmitted SMS text. */
    private String claimSubmittedSmsText(ClaimSubmissionSummary summary) {
        if (summary.resubmitted()) {
            return "Receipt resubmitted for review by " + summary.claimantName() + SUBJECT_SEPARATOR
                    + summary.claimPeriod() + SMS_BRAND_SUFFIX;
        }
        return "New staff claim submitted by " + summary.claimantName() + SUBJECT_SEPARATOR + summary.claimPeriod()
                + ", " + summary.amount() + SMS_BRAND_SUFFIX;
    }

    /** Builds the named WhatsApp body parameters for the claim-submitted template. */
    private List<Map<String, Object>> claimSubmittedWhatsappTemplateComponents(
            String approverName, ClaimSubmissionSummary summary, String claimAction) {
        return bodyParameters(List.of(
                namedParameter("approver_name", whatsappValue(approverName)),
                namedParameter("claimant_name", whatsappValue(summary.claimantName())),
                namedParameter("claim_period", whatsappValue(summary.claimPeriod())),
                namedParameter("amount", whatsappValue(summary.amount())),
                namedParameter("action", whatsappValue(claimAction))));
    }

    /** Renders the claim-approved content for all three channels. */
    private ClaimApprovedNotificationContent renderClaimApprovedContent(
            StaffClaim claim, Staff claimantStaff, String claimantStaffId, String baseCurrency) {
        ClaimDecisionSummary summary = new ClaimDecisionSummary(
                displayName(claimantStaff.getName(), claimantStaffId),
                nz(claim.getClaimPeriod()),
                money(baseCurrency, claim.getClaimAmount()));
        return new ClaimApprovedNotificationContent(
                claimApprovedSubject(summary),
                claimApprovedEmailBody(summary),
                claimApprovedSmsText(summary),
                CLAIM_DECISION_TEMPLATE_NAME,
                claimApprovedWhatsappTemplateComponents(summary));
    }

    /** Builds the claim-approved subject line. */
    private String claimApprovedSubject(ClaimDecisionSummary summary) {
        return "Your " + summary.claimPeriod() + " claim is approved.";
    }

    /** Renders the claim-approved email body through the existing template. */
    private String claimApprovedEmailBody(ClaimDecisionSummary summary) {
        return ClaimEmailTemplate.buildApprovedEmail(summary.claimantName(), summary.claimPeriod(), summary.amount());
    }

    /** Builds the claim-approved SMS text. */
    private String claimApprovedSmsText(ClaimDecisionSummary summary) {
        return "Your " + summary.claimPeriod() + " claim of " + summary.amount() + " is approved" + SMS_BRAND_SUFFIX;
    }

    /** Builds the named WhatsApp body parameters for the claim-decision template. */
    private List<Map<String, Object>> claimApprovedWhatsappTemplateComponents(ClaimDecisionSummary summary) {
        return bodyParameters(List.of(
                namedParameter("claimant_name", whatsappValue(summary.claimantName())),
                namedParameter("claim_ref", whatsappValue(summary.claimPeriod())),
                namedParameter("amount", whatsappValue(summary.amount())),
                namedParameter("outcome", whatsappValue(CLAIM_OUTCOME_APPROVED)),
                namedParameter("reason", WHATSAPP_BLANK_PLACEHOLDER)));
    }

    /** Reads the base currency on the business transaction; a read failure drops the code only. */
    private Uni<String> loadBaseCurrency(NotificationTransaction context) {
        return systemParameterService
                .loadParameter(context.transaction(), SystemParameterService.PARAM_BASE_CURRENCY)
                .onFailure()
                .recoverWithItem((String) null);
    }

    /** Resolves a staff display name on the transaction; a read failure falls back upstream. */
    private Uni<String> resolveStaffDisplayName(NotificationTransaction context, String staffId) {
        return staffRepository
                .findNameByStaffId(context.transaction(), staffId)
                .onFailure()
                .recoverWithItem((String) null);
    }

    /** The claimant-facing staff id: the recorded entry staff, else the owning staff. */
    private String resolveClaimantStaffId(StaffClaim claim) {
        return claim.getEntryStaff() != null ? claim.getEntryStaff() : claim.getStaffId();
    }

    /** The submitting staff id: the recorded entry staff, else the owning staff. */
    private String resolveSubmitterStaffId(StaffClaimDTO claim) {
        return claim.getEntryStaff() != null ? claim.getEntryStaff() : claim.getStaffId();
    }

    /** Falls back to the identifier when a display name is absent. */
    private String displayName(String name, String fallback) {
        return (name != null && !name.isBlank()) ? name : fallback;
    }

    /** Meta rejects blank template parameters, so blanks become a dash. */
    private String whatsappValue(String value) {
        return (value == null || value.isBlank()) ? WHATSAPP_BLANK_PLACEHOLDER : value;
    }

    /** Returns an empty string for a null value, matching the original notification text. */
    private String nz(String value) {
        return value == null ? "" : value;
    }

    /** Formats "{CCY} {amount}" and drops the code when the base currency is unknown. */
    private String money(String currency, BigDecimal amount) {
        String value = amount == null
                ? "0.00"
                : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return (currency == null || currency.isBlank()) ? value : currency + " " + value;
    }

    /** Wraps named parameters in the Meta body component shape. */
    private List<Map<String, Object>> bodyParameters(List<Map<String, Object>> parameters) {
        return List.of(Map.of("type", "body", "parameters", parameters));
    }

    /** Preserves the specification-defined parameter_name and text wire fields. */
    private Map<String, Object> namedParameter(String parameterName, String value) {
        return Map.of("type", "text", "parameter_name", parameterName, "text", value == null ? "" : value);
    }

    /** Identifies absent staff identifiers and contacts. */
    private boolean isAbsent(String value) {
        return value == null || value.isBlank();
    }

    /** The claim context carried from the approver parameter read into content staging. */
    private record ClaimSubmittedDispatch(
            NotificationTransaction context,
            StaffClaimDTO claim,
            String claimAction,
            String recipientStaffId,
            HrNotificationChannelToggles toggles) {}

    /** The resolved inputs the claim-submitted renderer needs. */
    private record ClaimSubmittedRenderRequest(
            StaffClaimDTO claim,
            String claimAction,
            Staff approverStaff,
            String recipientStaffId,
            String submitterName,
            String baseCurrency) {}

    /** The claimant-facing facts the claim-submitted subject, email, SMS and WhatsApp content share. */
    private record ClaimSubmissionSummary(
            String claimantName, String claimPeriod, String amount, boolean resubmitted) {}

    /** The claimant-facing facts the claim-approved subject, email, SMS and WhatsApp content share. */
    private record ClaimDecisionSummary(String claimantName, String claimPeriod, String amount) {}

    /** The channel-ready claim-submitted or claim-resubmitted content. */
    private record ClaimSubmittedNotificationContent(
            String emailSubject,
            String emailBody,
            String smsText,
            String whatsappTemplateName,
            List<Map<String, Object>> whatsappTemplateComponents)
            implements HrNotificationChannelContent {}

    /** The channel-ready claim-approved content. */
    private record ClaimApprovedNotificationContent(
            String emailSubject,
            String emailBody,
            String smsText,
            String whatsappTemplateName,
            List<Map<String, Object>> whatsappTemplateComponents)
            implements HrNotificationChannelContent {}
}
