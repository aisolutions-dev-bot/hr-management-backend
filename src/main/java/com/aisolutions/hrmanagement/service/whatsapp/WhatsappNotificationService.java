package com.aisolutions.hrmanagement.service.whatsapp;

import com.aisolutions.shared.service.whatsapp.MetaWhatsappService;
import com.aisolutions.shared.service.whatsapp.TemplateComponent;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Sends HR Management WhatsApp notifications via the Meta WhatsApp Cloud API, mirroring the
 * evaluation/jobtasks pattern: the blocking Meta call runs on a Vert.x worker thread and every
 * failure is swallowed, so the caller fires these off detached from the request transaction.
 *
 * <p>WhatsApp outbound uses <b>pre-approved templates</b> — each template below must exist and
 * be APPROVED in Meta Business Manager (named-variable syntax) before sends succeed, otherwise
 * Meta returns error 132001 ("template not found"). Meta also rejects blank parameters, so every
 * value is kept non-empty (blanks are sent as "-").
 *
 * <p>Templates this module sends:
 * <ul>
 *   <li>{@code hr_claim_submitted_v1} (en) — to the HR approver — named params:
 *       {@code approver_name, claimant_name, claim_period, amount, action}
 *       (action = "submitted" | "resubmitted")</li>
 *   <li>{@code hr_claim_decision_v1} (en) — to the claimant — named params:
 *       {@code claimant_name, claim_ref, amount, outcome, reason}
 *       (outcome = "approved" | "rejected"; reason = "-" when none)</li>
 *   <li>{@code hr_leave_submitted_v1} (en) — to the leave approver — named params:
 *       {@code approver_name, applicant_name, leave_type, period}</li>
 * </ul>
 */
@ApplicationScoped
public class WhatsappNotificationService {

    private static final Logger LOG = Logger.getLogger(WhatsappNotificationService.class);

    private static final String CLAIM_SUBMITTED_TEMPLATE_NAME = "hr_claim_submitted_v1";
    private static final String CLAIM_SUBMITTED_LANGUAGE_CODE = "en";

    private static final String CLAIM_DECISION_TEMPLATE_NAME = "hr_claim_decision_v1";
    private static final String CLAIM_DECISION_LANGUAGE_CODE = "en";

    private static final String LEAVE_SUBMITTED_TEMPLATE_NAME = "hr_leave_submitted_v1";
    private static final String LEAVE_SUBMITTED_LANGUAGE_CODE = "en";

    @Inject
    MetaWhatsappService metaWhatsappService;

    @Inject
    Vertx vertx;

    /** Sends {@code hr_claim_submitted_v1} to the approver (action = "submitted" | "resubmitted"). */
    public Uni<Boolean> sendClaimSubmitted(String mobile, String approverName, String claimantName,
            String claimPeriod, String amount, String action) {
        return dispatch(mobile, CLAIM_SUBMITTED_TEMPLATE_NAME, CLAIM_SUBMITTED_LANGUAGE_CODE,
                TemplateComponent.bodyNamed(
                        new TemplateComponent.NamedParameter("approver_name", wz(approverName)),
                        new TemplateComponent.NamedParameter("claimant_name", wz(claimantName)),
                        new TemplateComponent.NamedParameter("claim_period", wz(claimPeriod)),
                        new TemplateComponent.NamedParameter("amount", wz(amount)),
                        new TemplateComponent.NamedParameter("action", wz(action))));
    }

    /** Sends {@code hr_claim_decision_v1} to the claimant (outcome = "approved" | "rejected"). */
    public Uni<Boolean> sendClaimDecision(String mobile, String claimantName, String claimRef,
            String amount, String outcome, String reason) {
        return dispatch(mobile, CLAIM_DECISION_TEMPLATE_NAME, CLAIM_DECISION_LANGUAGE_CODE,
                TemplateComponent.bodyNamed(
                        new TemplateComponent.NamedParameter("claimant_name", wz(claimantName)),
                        new TemplateComponent.NamedParameter("claim_ref", wz(claimRef)),
                        new TemplateComponent.NamedParameter("amount", wz(amount)),
                        new TemplateComponent.NamedParameter("outcome", wz(outcome)),
                        new TemplateComponent.NamedParameter("reason", wz(reason))));
    }

    /** Sends {@code hr_leave_submitted_v1} to the leave approver. */
    public Uni<Boolean> sendLeaveSubmitted(String mobile, String approverName, String applicantName,
            String leaveType, String period) {
        return dispatch(mobile, LEAVE_SUBMITTED_TEMPLATE_NAME, LEAVE_SUBMITTED_LANGUAGE_CODE,
                TemplateComponent.bodyNamed(
                        new TemplateComponent.NamedParameter("approver_name", wz(approverName)),
                        new TemplateComponent.NamedParameter("applicant_name", wz(applicantName)),
                        new TemplateComponent.NamedParameter("leave_type", wz(leaveType)),
                        new TemplateComponent.NamedParameter("period", wz(period))));
    }

    /**
     * Runs the blocking {@link MetaWhatsappService#sendTemplate} call on a Vert.x worker thread,
     * logging and swallowing any failure so the caller always resolves.
     */
    private Uni<Boolean> dispatch(String mobile, String templateName, String languageCode,
            TemplateComponent body) {
        String normalizedMobile = normalizeMobileNumber(mobile);
        LOG.infof("[WhatsApp] Sending %s — to=%s", templateName, normalizedMobile);
        return vertx.executeBlocking(
                Uni.createFrom().item(() -> {
                    metaWhatsappService.sendTemplate(normalizedMobile, templateName, languageCode, List.of(body));
                    return true;
                }))
                .onFailure().invoke(err -> LOG.errorf(err, "[WhatsApp] sendTemplate FAILED — to=%s, template=%s, cause=%s",
                        normalizedMobile, templateName, err.getMessage()))
                .onFailure().recoverWithItem(false);
    }

    /** Strips a leading {@code +} so Meta receives a plain E.164 digit string. */
    private String normalizeMobileNumber(String mobileNumber) {
        if (mobileNumber == null) {
            return null;
        }
        return mobileNumber.startsWith("+") ? mobileNumber.substring(1) : mobileNumber;
    }

    /** Meta rejects blank template parameters — substitute a dash for any blank value. */
    private static String wz(String value) {
        return (value == null || value.isBlank()) ? "-" : value;
    }
}
