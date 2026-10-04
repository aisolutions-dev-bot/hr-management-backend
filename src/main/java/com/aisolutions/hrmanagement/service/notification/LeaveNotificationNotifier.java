package com.aisolutions.hrmanagement.service.notification;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.hrmanagement.entity.LeaveApplication;
import com.aisolutions.hrmanagement.entity.Staff;
import com.aisolutions.hrmanagement.repository.StaffRepository;
import com.aisolutions.shared.notification.NotificationTransaction;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

/**
 * Builds and stages the leave-submitted registry parameters for every approver on the
 * shared notification outbox. Delegates channel switches and staging to
 * {@link HrNotificationChannelDispatcher}, approver reads to {@link StaffRepository} and registry
 * parameters to {@link #buildLeaveTemplateContent}.
 */
@ApplicationScoped
public class LeaveNotificationNotifier {

    private static final Logger LOG = Logger.getLogger(LeaveNotificationNotifier.class);

    private static final String LEAVE_ACTION_CANCEL = "CANCEL";
    private static final String HALF_DAY_AM = "AM";
    private static final String HALF_DAY_PM = "PM";
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
            return recordNotificationSkip(context, "all", leave.getUniqId(), "approvers_missing");
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
            return recordNotificationSkip(context, "email,sms,whatsapp", leave.getUniqId(), "channels_disabled");
        }
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (String approverStaffId : approverStaffIds) {
            if (isAbsent(approverStaffId)) {
                recordNotificationSkip(context, "all", leave.getUniqId(), "approver_identifier_missing");
                continue;
            }
            chain = chain.flatMap(ignored -> queueLeaveApproverChannels(context, leave, approverStaffId, toggles));
        }
        return chain;
    }

    /** Reads one approver and delegates content staging to {@link #stageLeaveApproverContent}. */
    private Uni<Void> queueLeaveApproverChannels(
            NotificationTransaction context,
            LeaveApplication leave,
            String approverStaffId,
            HrNotificationChannelToggles toggles) {
        LeaveApproverDispatch dispatch = new LeaveApproverDispatch(context, leave, approverStaffId, toggles);
        return staffRepository
                .findByStaffId(context.transaction(), approverStaffId)
                .flatMap(approverStaff -> stageLeaveApproverContent(dispatch, approverStaff));
    }

    /** Audits absent staff or delegates rendered content to the shared channel dispatcher. */
    private Uni<Void> stageLeaveApproverContent(LeaveApproverDispatch dispatch, Staff approverStaff) {
        if (approverStaff == null) {
            return recordNotificationSkip(
                    dispatch.context(), "all", dispatch.leave().getUniqId(), "staff_missing");
        }
        LeaveNotificationContent content =
                buildLeaveTemplateContent(dispatch.leave(), approverStaff, dispatch.approverStaffId());
        return notificationChannelDispatcher.stageStaffChannels(
                new HrNotificationChannelDispatcher.StaffChannelDispatch(
                        dispatch.context(),
                        dispatch.toggles(),
                        approverStaff,
                        content,
                        dispatch.leave().getUniqId()));
    }

    /** Carries the leave identity and channel switches through approver resolution. */
    private record LeaveApproverDispatch(
            NotificationTransaction context,
            LeaveApplication leave,
            String approverStaffId,
            HrNotificationChannelToggles toggles) {}

    /** Builds the registry parameter map for the leave-submitted template. */
    private LeaveNotificationContent buildLeaveTemplateContent(
            LeaveApplication leave, Staff approverStaff, String approverStaffId) {
        boolean cancelled = LEAVE_ACTION_CANCEL.equals(leave.getLeaveAction());
        LeaveSubmissionSummary summary = new LeaveSubmissionSummary(
                displayName(leave.getStaffName(), leave.getStaffId()),
                nz(leave.getLeaveType()),
                leavePeriodText(leave),
                cancelled);
        String approverName = displayName(approverStaff.getName(), approverStaffId);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("approver_name", whatsappValue(approverName));
        parameters.put("applicant_name", whatsappValue(summary.applicantName()));
        parameters.put(
                "leave_type",
                whatsappValue(summary.cancelled() ? summary.leaveType() + " (cancellation)" : summary.leaveType()));
        parameters.put("period", whatsappValue(summary.periodText().trim()));
        parameters.put("action", summary.cancelled() ? "cancelled" : "submitted");
        parameters.put("remarks", nz(leave.getRemarks()));
        return new LeaveNotificationContent(LEAVE_SUBMITTED_TEMPLATE_NAME, parameters);
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

    /** Records an intentional skip without recipient contact information. */
    private Uni<Void> recordNotificationSkip(
            NotificationTransaction context, String channel, Object businessIdentity, String reason) {
        LOG.warnf(
                "notification_skipped company_id=%s channel=%s business_identity=%s reason=%s",
                context.companyId(), channel, businessIdentity, reason);
        return Uni.createFrom().voidItem();
    }

    /** Identifies absent staff identifiers and contacts. */
    private boolean isAbsent(String value) {
        return value == null || value.isBlank();
    }

    /** Carries the registry template and parameter map shared by channel producers. */
    private record LeaveNotificationContent(String templateName, Map<String, Object> templateParameters)
            implements HrNotificationChannelContent {

        @Override
        public String languageCode() {
            return "en";
        }
    }

    /** The applicant-facing facts the leave subject, email, SMS and WhatsApp content share. */
    private record LeaveSubmissionSummary(
            String applicantName, String leaveType, String periodText, boolean cancelled) {}
}
