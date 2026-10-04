package com.aisolutions.hrmanagement.service.notification;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.hrmanagement.entity.LeaveApplication;
import com.aisolutions.hrmanagement.entity.Staff;
import com.aisolutions.hrmanagement.repository.StaffRepository;
import com.aisolutions.hrmanagement.service.leave.LeaveEmailTemplate;
import com.aisolutions.shared.notification.NotificationTransaction;
import io.smallrye.mutiny.Uni;

/**
 * Builds and stages the leave-submitted email, SMS and WhatsApp content for every approver on the
 * shared notification outbox. Delegates channel switches and staging to
 * {@link HrNotificationChannelDispatcher}, approver reads to {@link StaffRepository} and content
 * rendering to the private {@code renderLeaveContent} helper.
 */
@ApplicationScoped
public class LeaveNotificationNotifier {

    private static final String LEAVE_ACTION_CANCEL = "CANCEL";
    private static final String HALF_DAY_AM = "AM";
    private static final String HALF_DAY_PM = "PM";
    private static final String SUBJECT_SEPARATOR = " - ";
    private static final String SMS_BRAND_SUFFIX = ". - AI Solutions";
    private static final String WHATSAPP_BLANK_PLACEHOLDER = "-";
    private static final String LEAVE_SUBMITTED_TEMPLATE_NAME = "hr_leave_submitted_v1";

    @Inject
    HrNotificationChannelDispatcher notificationChannelDispatcher;

    @Inject
    StaffRepository staffRepository;

    /**
     * Stages the leave-submitted content for every approver. Delegates tenant switches to
     * {@link HrNotificationChannelDispatcher#loadChannelToggles} and per-approver work to
     * {@link #queueLeaveApprovers}.
     */
    public Uni<Void> notifyLeaveSubmitted(
            NotificationTransaction context, LeaveApplication leave, List<String> approverStaffIds) {
        if (approverStaffIds == null || approverStaffIds.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return notificationChannelDispatcher
                .loadChannelToggles(context.transaction())
                .flatMap(toggles -> queueLeaveApprovers(context, leave, approverStaffIds, toggles));
    }

    /** Skips disabled tenants and stages each named approver sequentially on one connection. */
    private Uni<Void> queueLeaveApprovers(
            NotificationTransaction context,
            LeaveApplication leave,
            List<String> approverStaffIds,
            HrNotificationChannelToggles toggles) {
        if (!toggles.anyEnabled()) {
            return Uni.createFrom().voidItem();
        }
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (String approverStaffId : approverStaffIds) {
            if (isAbsent(approverStaffId)) {
                continue;
            }
            chain = chain.flatMap(ignored -> queueLeaveApproverChannels(context, leave, approverStaffId, toggles));
        }
        return chain;
    }

    /** Resolves one approver, renders the content and delegates staging to the dispatcher. */
    private Uni<Void> queueLeaveApproverChannels(
            NotificationTransaction context,
            LeaveApplication leave,
            String approverStaffId,
            HrNotificationChannelToggles toggles) {
        return staffRepository
                .findByStaffId(context.transaction(), approverStaffId)
                .onFailure()
                .recoverWithItem((Staff) null)
                .flatMap(approverStaff -> {
                    if (approverStaff == null) {
                        return Uni.createFrom().voidItem();
                    }
                    LeaveNotificationContent content = renderLeaveContent(leave, approverStaff, approverStaffId);
                    return notificationChannelDispatcher.stageStaffChannels(context, toggles, approverStaff, content);
                });
    }

    /** Renders the leave subject, email HTML, SMS text and WhatsApp parameters for one approver. */
    private LeaveNotificationContent renderLeaveContent(
            LeaveApplication leave, Staff approverStaff, String approverStaffId) {
        boolean cancelled = LEAVE_ACTION_CANCEL.equals(leave.getLeaveAction());
        LeaveSubmissionSummary summary = new LeaveSubmissionSummary(
                displayName(leave.getStaffName(), leave.getStaffId()),
                nz(leave.getLeaveType()),
                leavePeriodText(leave),
                cancelled);
        String approverName = displayName(approverStaff.getName(), approverStaffId);
        return new LeaveNotificationContent(
                leaveSubject(summary),
                leaveEmailBody(approverName, summary, leave.getRemarks()),
                leaveSmsText(summary),
                LEAVE_SUBMITTED_TEMPLATE_NAME,
                leaveWhatsappTemplateComponents(approverName, summary));
    }

    /** Builds the subject line the original leave notification used. */
    private String leaveSubject(LeaveSubmissionSummary summary) {
        String prefix = summary.cancelled() ? "Leave cancellation request from " : "Leave application from ";
        return prefix + summary.applicantName() + SUBJECT_SEPARATOR + summary.leaveType();
    }

    /** Renders the leave email body through the existing template. */
    private String leaveEmailBody(String approverName, LeaveSubmissionSummary summary, String remarks) {
        return LeaveEmailTemplate.buildSubmittedEmail(
                approverName,
                summary.applicantName(),
                summary.leaveType(),
                summary.periodText(),
                summary.cancelled(),
                remarks);
    }

    /** Builds the SMS text the original leave notification used. */
    private String leaveSmsText(LeaveSubmissionSummary summary) {
        String prefix = summary.cancelled() ? "Leave cancellation request from " : "Leave application from ";
        return prefix
                + summary.applicantName()
                + SUBJECT_SEPARATOR
                + summary.leaveType()
                + summary.periodText()
                + SMS_BRAND_SUFFIX;
    }

    /** Builds the named WhatsApp body parameters for the leave-submitted template. */
    private List<Map<String, Object>> leaveWhatsappTemplateComponents(
            String approverName, LeaveSubmissionSummary summary) {
        String whatsappLeaveType = summary.cancelled() ? summary.leaveType() + " (cancellation)" : summary.leaveType();
        return bodyParameters(List.of(
                namedParameter("approver_name", whatsappValue(approverName)),
                namedParameter("applicant_name", whatsappValue(summary.applicantName())),
                namedParameter("leave_type", whatsappValue(whatsappLeaveType)),
                namedParameter("period", whatsappValue(summary.periodText().trim()))));
    }

    /** Builds the human-readable leave period text the original notification used. */
    private String leavePeriodText(LeaveApplication leave) {
        if (leave.getFromDate() == null) {
            return "";
        }
        String periodText = " from " + leave.getFromDate();
        if (leave.getToDate() != null && !leave.getToDate().equals(leave.getFromDate())) {
            periodText += " to " + leave.getToDate();
        }
        if (isHalfDay(leave.getHalfDayPeriod())) {
            periodText += " (" + leave.getHalfDayPeriod() + " half-day)";
        }
        return periodText;
    }

    /** Reports whether a half-day period is one of the two supported markers. */
    private boolean isHalfDay(String halfDayPeriod) {
        if (halfDayPeriod == null) {
            return false;
        }
        String normalizedPeriod = halfDayPeriod.trim().toUpperCase();
        return HALF_DAY_AM.equals(normalizedPeriod) || HALF_DAY_PM.equals(normalizedPeriod);
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

    /** The channel-ready leave content shared by the email, SMS and WhatsApp channels. */
    private record LeaveNotificationContent(
            String emailSubject,
            String emailBody,
            String smsText,
            String whatsappTemplateName,
            List<Map<String, Object>> whatsappTemplateComponents)
            implements HrNotificationChannelContent {}

    /** The applicant-facing facts the leave subject, email, SMS and WhatsApp content share. */
    private record LeaveSubmissionSummary(
            String applicantName, String leaveType, String periodText, boolean cancelled) {}
}
