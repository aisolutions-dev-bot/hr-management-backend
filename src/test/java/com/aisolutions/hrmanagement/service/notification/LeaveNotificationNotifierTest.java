package com.aisolutions.hrmanagement.service.notification;

import java.time.LocalDate;
import java.util.List;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
                .enqueueEmail(
                        any(NotificationTransaction.class),
                        eq(APPROVER_EMAIL),
                        eq("Leave application from Alice - ANNUAL"),
                        contains("Hi Bob"));
        verify(notificationPublisher)
                .enqueueSms(
                        any(NotificationTransaction.class),
                        eq(APPROVER_MOBILE),
                        contains("Leave application from Alice"));
        verify(notificationPublisher)
                .enqueueWhatsappTemplate(
                        any(NotificationTransaction.class),
                        eq(APPROVER_MOBILE),
                        eq(LEAVE_TEMPLATE_NAME),
                        eq(NOTIFICATION_LANGUAGE),
                        anyList());
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
                .enqueueEmail(any(NotificationTransaction.class), anyString(), anyString(), anyString());
        verify(notificationPublisher, never()).enqueueSms(any(NotificationTransaction.class), anyString(), anyString());
        verify(notificationPublisher, never())
                .enqueueWhatsappTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyList());
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
        when(systemParameterService.isNotificationEmailEnabled(any(SqlClient.class)))
                .thenReturn(Uni.createFrom().item(enabled));
        when(systemParameterService.isNotificationSmsEnabled(any(SqlClient.class)))
                .thenReturn(Uni.createFrom().item(enabled));
        when(systemParameterService.isNotificationWhatsappEnabled(any(SqlClient.class)))
                .thenReturn(Uni.createFrom().item(enabled));
    }

    /** Stubs the approver lookup to return the supplied staff row. */
    private void stubStaffLookup(String staffId, Staff staff) {
        when(staffRepository.findByStaffId(any(SqlClient.class), eq(staffId)))
                .thenReturn(Uni.createFrom().item(staff));
    }

    /** Makes every publisher call succeed so the staging chain completes. */
    private void stubPublisherSuccess() {
        lenient()
                .when(notificationPublisher.enqueueEmail(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString()))
                .thenReturn(Uni.createFrom().voidItem());
        lenient()
                .when(notificationPublisher.enqueueSms(any(NotificationTransaction.class), anyString(), anyString()))
                .thenReturn(Uni.createFrom().voidItem());
        lenient()
                .when(notificationPublisher.enqueueWhatsappTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyList()))
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
