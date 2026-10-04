package com.aisolutions.hrmanagement.service.notification;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.aisolutions.hrmanagement.entity.LeaveApplication;
import com.aisolutions.hrmanagement.entity.Staff;
import com.aisolutions.hrmanagement.repository.StaffRepository;
import com.aisolutions.hrmanagement.service.SystemParameterService;
import com.aisolutions.shared.notification.NotificationPublisher;
import com.aisolutions.shared.notification.NotificationTransaction;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit coverage of the leave-submitted notifier's channel routing and rendered content. */
@ExtendWith(MockitoExtension.class)
class LeaveNotificationNotifierTest {

    private static final String COMPANY_ID = "db_test2";
    private static final String APPROVER_ID = "APPROVER-1";
    private static final String APPROVER_EMAIL = "approver@example.com";
    private static final String APPROVER_MOBILE = "+60123456789";
    private static final String APPROVER_NAME = "Bob";
    private static final String APPLICANT_NAME = "Alice";
    private static final String LEAVE_TYPE_ANNUAL = "ANNUAL";
    private static final String LEAVE_TEMPLATE_NAME = "hr_leave_submitted_v1";
    private static final String NOTIFICATION_LANGUAGE = "en";

    /** A failed staff lookup aborts staging so a leave cannot commit without its notification. */
    @Test
    void propagatesApproverStaffLookupFailure() {
        stubAllChannelSwitches(true);
        when(staffRepository.findByStaffId(any(SqlClient.class), eq(APPROVER_ID)))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("staff database unavailable")));

        assertThrows(
                IllegalStateException.class,
                () -> notifier()
                        .notifyLeaveSubmitted(context(), leaveApplication(), List.of(APPROVER_ID))
                        .await()
                        .indefinitely());
        verify(notificationPublisher, never())
                .enqueueEmailTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyMap());
    }

    @Mock
    NotificationPublisher notificationPublisher;

    @Mock
    SystemParameterService systemParameterService;

    @Mock
    StaffRepository staffRepository;

    /** Stages email, SMS and WhatsApp for a leave when every tenant switch is on. */
    @Test
    void stagesEveryEnabledChannelForLeaveSubmitted() {
        stubAllChannelSwitches(true);
        stubStaffLookup(APPROVER_ID, approver());
        stubPublisherSuccess();

        notifier()
                .notifyLeaveSubmitted(context(), leaveApplication(), List.of(APPROVER_ID))
                .await()
                .indefinitely();

        verify(notificationPublisher)
                .enqueueEmailTemplate(
                        any(NotificationTransaction.class),
                        eq(APPROVER_EMAIL),
                        eq(LEAVE_TEMPLATE_NAME),
                        eq(NOTIFICATION_LANGUAGE),
                        argThat((Map<String, Object> parameters) ->
                                parameters.get("approver_name").equals(APPROVER_NAME)
                                        && parameters.get("applicant_name").equals(APPLICANT_NAME)
                                        && parameters.get("action").equals("submitted")));
        verify(notificationPublisher)
                .enqueueSmsTemplate(
                        any(NotificationTransaction.class),
                        eq(APPROVER_MOBILE),
                        eq(LEAVE_TEMPLATE_NAME),
                        eq(NOTIFICATION_LANGUAGE),
                        argThat((Map<String, Object> parameters) ->
                                parameters.get("applicant_name").equals(APPLICANT_NAME)
                                        && parameters.get("period").toString().contains("2026-09-10")));
        verify(notificationPublisher)
                .enqueueWhatsappTemplate(
                        any(NotificationTransaction.class),
                        eq(APPROVER_MOBILE),
                        eq(LEAVE_TEMPLATE_NAME),
                        eq(NOTIFICATION_LANGUAGE),
                        anyMap());
    }

    /** Skips every channel when the tenant has them all switched off. */
    @Test
    void skipsAllChannelsWhenTenantSwitchesAreOff() {
        stubAllChannelSwitches(false);

        notifier()
                .notifyLeaveSubmitted(context(), leaveApplication(), List.of(APPROVER_ID))
                .await()
                .indefinitely();

        verify(notificationPublisher, never())
                .enqueueEmailTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyMap());
        verify(notificationPublisher, never())
                .enqueueSmsTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyMap());
        verify(notificationPublisher, never())
                .enqueueWhatsappTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyMap());
    }

    /** Builds a notifier whose dispatcher shares the stubbed publisher and parameter service. */
    private LeaveNotificationNotifier notifier() {
        HrNotificationChannelDispatcher dispatcher = new HrNotificationChannelDispatcher();
        dispatcher.notificationPublisher = notificationPublisher;
        dispatcher.systemParameterService = systemParameterService;
        LeaveNotificationNotifier notifier = new LeaveNotificationNotifier();
        notifier.notificationChannelDispatcher = dispatcher;
        notifier.staffRepository = staffRepository;
        return notifier;
    }

    /** Wraps a mocked connection in the transaction envelope the notifier expects. */
    private NotificationTransaction context() {
        return new NotificationTransaction(org.mockito.Mockito.mock(SqlClient.class), COMPANY_ID);
    }

    /** Stubs all three tenant switches to the same value. */
    private void stubAllChannelSwitches(boolean enabled) {
        when(systemParameterService.loadParameter(
                        any(SqlClient.class), eq(SystemParameterService.PARAM_NOTIFICATION_EMAIL)))
                .thenReturn(Uni.createFrom().item(Boolean.toString(enabled)));
        when(systemParameterService.loadParameter(
                        any(SqlClient.class), eq(SystemParameterService.PARAM_NOTIFICATION_SMS)))
                .thenReturn(Uni.createFrom().item(Boolean.toString(enabled)));
        when(systemParameterService.loadParameter(
                        any(SqlClient.class), eq(SystemParameterService.PARAM_NOTIFICATION_WHATSAPP)))
                .thenReturn(Uni.createFrom().item(Boolean.toString(enabled)));
    }

    /** Stubs the approver lookup to return the supplied staff row. */
    private void stubStaffLookup(String staffId, Staff staff) {
        when(staffRepository.findByStaffId(any(SqlClient.class), eq(staffId)))
                .thenReturn(Uni.createFrom().item(staff));
    }

    /** Makes every publisher call succeed so the staging chain completes. */
    private void stubPublisherSuccess() {
        lenient()
                .when(notificationPublisher.enqueueEmailTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(Uni.createFrom().voidItem());
        lenient()
                .when(notificationPublisher.enqueueSmsTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(Uni.createFrom().voidItem());
        lenient()
                .when(notificationPublisher.enqueueWhatsappTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(Uni.createFrom().voidItem());
    }

    /** Builds the leave used by every test in this class. */
    private LeaveApplication leaveApplication() {
        LeaveApplication leave = new LeaveApplication();
        leave.setStaffId("SUBMITTER-1");
        leave.setStaffName(APPLICANT_NAME);
        leave.setLeaveAction("APPLY");
        leave.setLeaveType(LEAVE_TYPE_ANNUAL);
        leave.setFromDate(LocalDate.of(2026, 9, 10));
        leave.setToDate(LocalDate.of(2026, 9, 11));
        leave.setRemarks("Trip");
        return leave;
    }

    /** Builds the approver staff row used by every test in this class. */
    private Staff approver() {
        Staff staff = new Staff();
        staff.setStaffId(APPROVER_ID);
        staff.setName(APPROVER_NAME);
        staff.setEmailCompany(APPROVER_EMAIL);
        staff.setTelMobile(APPROVER_MOBILE);
        return staff;
    }
}
