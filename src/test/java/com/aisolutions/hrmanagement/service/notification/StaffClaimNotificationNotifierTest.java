package com.aisolutions.hrmanagement.service.notification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.aisolutions.hrmanagement.dto.StaffClaimDTO;
import com.aisolutions.hrmanagement.entity.Staff;
import com.aisolutions.hrmanagement.entity.StaffClaim;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit coverage of the staff-claim notifier's channel routing and rendered content. */
@ExtendWith(MockitoExtension.class)
class StaffClaimNotificationNotifierTest {

    private static final String COMPANY_ID = "db_test2";
    private static final String APPROVER_ID = "APPROVER-1";
    private static final String APPROVER_EMAIL = "approver@example.com";
    private static final String APPROVER_MOBILE = "+60123456789";
    private static final String SUBMITTER_ID = "SUBMITTER-1";
    private static final String CLAIMANT_ID = "CLAIMANT-1";
    private static final String CLAIM_PERIOD = "JULY-2026";
    private static final BigDecimal CLAIM_AMOUNT = new BigDecimal("128.00");
    private static final String BASE_CURRENCY = "SGD";
    private static final String CLAIM_ACTION_SUBMITTED = "submitted";
    private static final String CLAIM_ACTION_RESUBMITTED = "resubmitted";
    private static final String CLAIM_OUTCOME_APPROVED = "approved";
    private static final String CLAIM_SUBMITTED_TEMPLATE_NAME = "hr_claim_submitted_v1";
    private static final String CLAIM_DECISION_TEMPLATE_NAME = "hr_claim_decision_v1";
    private static final String NOTIFICATION_LANGUAGE = "en";

    @Mock
    NotificationPublisher notificationPublisher;

    @Mock
    SystemParameterService systemParameterService;

    @Mock
    StaffRepository staffRepository;

    /** Stages only the email channel for a submitted claim when SMS and WhatsApp are off. */
    @Test
    void stagesOnlyEmailForClaimSubmittedWhenOtherChannelsAreOff() {
        stubApproverParameter(APPROVER_ID);
        stubChannelSwitches(true, false, false);
        stubBaseCurrency(BASE_CURRENCY);
        stubStaffLookup(APPROVER_ID, approver());
        stubSubmitterName(SUBMITTER_ID, "Alice");
        stubPublisherSuccess();

        notifier()
                .notifyClaimSubmitted(context(), claimDto(), CLAIM_ACTION_SUBMITTED)
                .await()
                .indefinitely();

        verify(notificationPublisher)
                .enqueueEmail(
                        any(NotificationTransaction.class),
                        eq(APPROVER_EMAIL),
                        eq("New staff claim submitted by Alice - JULY-2026"),
                        contains("SGD 128.00"));
        verify(notificationPublisher, never()).enqueueSms(any(NotificationTransaction.class), anyString(), anyString());
        verify(notificationPublisher, never())
                .enqueueWhatsappTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyList());
    }

    /** Stages the resubmitted subject for a claim whose receipt was resubmitted. */
    @Test
    void stagesResubmittedSubjectForClaimResubmitted() {
        stubApproverParameter(APPROVER_ID);
        stubChannelSwitches(true, false, false);
        stubBaseCurrency(BASE_CURRENCY);
        stubStaffLookup(APPROVER_ID, approver());
        stubSubmitterName(SUBMITTER_ID, "Alice");
        stubPublisherSuccess();

        notifier()
                .notifyClaimSubmitted(context(), claimDto(), CLAIM_ACTION_RESUBMITTED)
                .await()
                .indefinitely();

        verify(notificationPublisher)
                .enqueueEmail(
                        any(NotificationTransaction.class),
                        eq(APPROVER_EMAIL),
                        eq("Receipt resubmitted for review by Alice - JULY-2026"),
                        anyString());
    }

    /** Stages the approved decision template for the claimant when WhatsApp is the only channel on. */
    @Test
    void stagesApprovedDecisionWhatsappForClaimant() {
        stubChannelSwitches(false, false, true);
        stubBaseCurrency(BASE_CURRENCY);
        stubStaffLookup(CLAIMANT_ID, claimant());
        stubPublisherSuccess();

        notifier().notifyClaimApproved(context(), claim()).await().indefinitely();

        verify(notificationPublisher)
                .enqueueWhatsappTemplate(
                        any(NotificationTransaction.class),
                        eq(APPROVER_MOBILE),
                        eq(CLAIM_DECISION_TEMPLATE_NAME),
                        eq(NOTIFICATION_LANGUAGE),
                        argThat((List<Map<String, Object>> components) -> hasApprovedDecision(components)));
        verify(notificationPublisher, never())
                .enqueueEmail(any(NotificationTransaction.class), anyString(), anyString(), anyString());
        verify(notificationPublisher, never()).enqueueSms(any(NotificationTransaction.class), anyString(), anyString());
    }

    /** Skips every channel when the tenant has them all switched off. */
    @Test
    void skipsAllChannelsWhenTenantSwitchesAreOff() {
        stubApproverParameter(APPROVER_ID);
        stubChannelSwitches(false, false, false);

        notifier()
                .notifyClaimSubmitted(context(), claimDto(), CLAIM_ACTION_SUBMITTED)
                .await()
                .indefinitely();

        verify(notificationPublisher, never())
                .enqueueEmail(any(NotificationTransaction.class), anyString(), anyString(), anyString());
        verify(notificationPublisher, never()).enqueueSms(any(NotificationTransaction.class), anyString(), anyString());
        verify(notificationPublisher, never())
                .enqueueWhatsappTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyList());
    }

    /** Reports whether the rendered components carry the period and approved outcome. */
    private boolean hasApprovedDecision(List<Map<String, Object>> components) {
        String renderedComponents = components.toString();
        return renderedComponents.contains(CLAIM_PERIOD) && renderedComponents.contains(CLAIM_OUTCOME_APPROVED);
    }

    /** Builds a notifier whose dispatcher shares the stubbed publisher and parameter service. */
    private StaffClaimNotificationNotifier notifier() {
        HrNotificationChannelDispatcher dispatcher = new HrNotificationChannelDispatcher();
        dispatcher.notificationPublisher = notificationPublisher;
        dispatcher.systemParameterService = systemParameterService;
        StaffClaimNotificationNotifier notifier = new StaffClaimNotificationNotifier();
        notifier.notificationChannelDispatcher = dispatcher;
        notifier.staffRepository = staffRepository;
        notifier.systemParameterService = systemParameterService;
        return notifier;
    }

    /** Wraps a mocked connection in the transaction envelope the notifier expects. */
    private NotificationTransaction context() {
        return new NotificationTransaction(org.mockito.Mockito.mock(SqlClient.class), COMPANY_ID);
    }

    /** Stubs the HR approver parameter the claim-submitted flow resolves. */
    private void stubApproverParameter(String approverStaffId) {
        when(systemParameterService.loadParameter(
                        any(SqlClient.class), eq(StaffClaimNotificationNotifier.PARAM_HR_APPROVER)))
                .thenReturn(Uni.createFrom().item(approverStaffId));
    }

    /** Stubs the tenant base currency parameter the amount rendering resolves. */
    private void stubBaseCurrency(String baseCurrency) {
        when(systemParameterService.loadParameter(any(SqlClient.class), eq(SystemParameterService.PARAM_BASE_CURRENCY)))
                .thenReturn(Uni.createFrom().item(baseCurrency));
    }

    /** Stubs each tenant channel switch independently. */
    private void stubChannelSwitches(boolean emailEnabled, boolean smsEnabled, boolean whatsappEnabled) {
        when(systemParameterService.isNotificationEmailEnabled(any(SqlClient.class)))
                .thenReturn(Uni.createFrom().item(emailEnabled));
        when(systemParameterService.isNotificationSmsEnabled(any(SqlClient.class)))
                .thenReturn(Uni.createFrom().item(smsEnabled));
        when(systemParameterService.isNotificationWhatsappEnabled(any(SqlClient.class)))
                .thenReturn(Uni.createFrom().item(whatsappEnabled));
    }

    /** Stubs the recipient staff lookup to return the supplied row. */
    private void stubStaffLookup(String staffId, Staff staff) {
        when(staffRepository.findByStaffId(any(SqlClient.class), eq(staffId)))
                .thenReturn(Uni.createFrom().item(staff));
    }

    /** Stubs the submitter display-name lookup. */
    private void stubSubmitterName(String staffId, String name) {
        when(staffRepository.findNameByStaffId(any(SqlClient.class), eq(staffId)))
                .thenReturn(Uni.createFrom().item(name));
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

    /** Builds the claim transfer object used by the submitted and resubmitted tests. */
    private StaffClaimDTO claimDto() {
        StaffClaimDTO claim = new StaffClaimDTO();
        claim.setEntryStaff(SUBMITTER_ID);
        claim.setClaimPeriod(CLAIM_PERIOD);
        claim.setClaimAmount(CLAIM_AMOUNT);
        return claim;
    }

    /** Builds the claim header used by the approved test. */
    private StaffClaim claim() {
        StaffClaim claim = new StaffClaim();
        claim.setEntryStaff(CLAIMANT_ID);
        claim.setClaimPeriod(CLAIM_PERIOD);
        claim.setClaimAmount(CLAIM_AMOUNT);
        return claim;
    }

    /** Builds the approver staff row used by the submitted tests. */
    private Staff approver() {
        Staff staff = new Staff();
        staff.setStaffId(APPROVER_ID);
        staff.setName("Bob");
        staff.setEmailCompany(APPROVER_EMAIL);
        staff.setTelMobile(APPROVER_MOBILE);
        return staff;
    }

    /** Builds the claimant staff row used by the approved test. */
    private Staff claimant() {
        Staff staff = new Staff();
        staff.setStaffId(CLAIMANT_ID);
        staff.setName("Alice");
        staff.setEmailCompany("claimant@example.com");
        staff.setTelMobile(APPROVER_MOBILE);
        return staff;
    }
}
