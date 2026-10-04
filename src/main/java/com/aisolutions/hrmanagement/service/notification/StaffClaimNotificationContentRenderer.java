package com.aisolutions.hrmanagement.service.notification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import com.aisolutions.hrmanagement.dto.StaffClaimDTO;
import com.aisolutions.hrmanagement.entity.Staff;
import com.aisolutions.hrmanagement.entity.StaffClaim;

/** Builds registry parameters for claim-submitted and claim-approved notifications. */
final class StaffClaimNotificationContentRenderer {

    private static final String CLAIM_OUTCOME_APPROVED = "approved";
    private static final String WHATSAPP_BLANK_PLACEHOLDER = "-";
    private static final String TEMPLATE_LANGUAGE_CODE = "en";
    private static final String CLAIM_SUBMITTED_TEMPLATE_NAME = "hr_claim_submitted_v1";
    private static final String CLAIM_DECISION_TEMPLATE_NAME = "hr_claim_decision_v1";

    /** Prevents instances of the stateless parameter factory. */
    private StaffClaimNotificationContentRenderer() {}

    /** Builds registry parameters for the submitted or resubmitted claim template. */
    static HrNotificationChannelContent buildClaimSubmittedContent(ClaimSubmittedRenderRequest request) {
        ClaimSubmissionSummary summary = new ClaimSubmissionSummary(
                displayName(request.submitterName(), resolveSubmitterStaffId(request.claim())),
                nz(request.claim().getClaimPeriod()),
                money(request.baseCurrency(), request.claim().getClaimAmount()));
        String approverName = displayName(request.approverStaff().getName(), request.recipientStaffId());
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("approver_name", whatsappValue(approverName));
        parameters.put("claimant_name", whatsappValue(summary.claimantName()));
        parameters.put("claim_period", whatsappValue(summary.claimPeriod()));
        parameters.put("amount", whatsappValue(summary.amount()));
        parameters.put("action", whatsappValue(nz(request.claimAction())));
        return new ClaimNotificationContent(CLAIM_SUBMITTED_TEMPLATE_NAME, parameters);
    }

    /** Builds registry parameters for the approved claim template. */
    static HrNotificationChannelContent buildClaimApprovedContent(
            StaffClaim claim, Staff claimantStaff, String claimantStaffId, String baseCurrency) {
        ClaimDecisionSummary summary = new ClaimDecisionSummary(
                displayName(claimantStaff.getName(), claimantStaffId),
                nz(claim.getClaimPeriod()),
                money(baseCurrency, claim.getClaimAmount()));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("claimant_name", whatsappValue(summary.claimantName()));
        parameters.put("claim_ref", whatsappValue(summary.claimPeriod()));
        parameters.put("amount", whatsappValue(summary.amount()));
        parameters.put("outcome", whatsappValue(CLAIM_OUTCOME_APPROVED));
        parameters.put("reason", WHATSAPP_BLANK_PLACEHOLDER);
        return new ClaimNotificationContent(CLAIM_DECISION_TEMPLATE_NAME, parameters);
    }

    /** Resolves the submitting staff identity used when the display name is absent. */
    private static String resolveSubmitterStaffId(StaffClaimDTO claim) {
        return claim.getEntryStaff() != null ? claim.getEntryStaff() : claim.getStaffId();
    }

    /** Falls back to the identifier when a display name is absent. */
    private static String displayName(String name, String fallback) {
        return (name != null && !name.isBlank()) ? name : fallback;
    }

    /** Meta rejects blank template parameters, so blanks become a dash. */
    private static String whatsappValue(String value) {
        return (value == null || value.isBlank()) ? WHATSAPP_BLANK_PLACEHOLDER : value;
    }

    /** Returns an empty string for a null value, matching the original notification text. */
    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /** Formats "{CCY} {amount}" and drops the code when the base currency is unknown. */
    private static String money(String currency, BigDecimal amount) {
        String value = amount == null
                ? "0.00"
                : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return (currency == null || currency.isBlank()) ? value : currency + " " + value;
    }

    /** The resolved inputs the claim-submitted renderer needs. */
    record ClaimSubmittedRenderRequest(
            StaffClaimDTO claim,
            String claimAction,
            Staff approverStaff,
            String recipientStaffId,
            String submitterName,
            String baseCurrency) {}

    /** The claimant-facing facts the claim-submitted template uses. */
    private record ClaimSubmissionSummary(String claimantName, String claimPeriod, String amount) {}

    /** The claimant-facing facts the claim-approved template uses. */
    private record ClaimDecisionSummary(String claimantName, String claimPeriod, String amount) {}

    /** Carries the registry template and parameter map shared by channel producers. */
    private record ClaimNotificationContent(String templateName, Map<String, Object> templateParameters)
            implements HrNotificationChannelContent {

        @Override
        public String languageCode() {
            return TEMPLATE_LANGUAGE_CODE;
        }
    }
}
