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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
    private static final String SUBMITTER_NAME = "Alice";
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

    /** An empty recipient list (no flow tier, no fallback approver) skips every channel. */
    @Test
    void skipsEveryChannelWhenNoRecipientIsResolved() {
        notifier()
                .notifyClaimSubmitted(context(), claimDto(), CLAIM_ACTION_SUBMITTED, List.of())
                .await()
                .indefinitely();

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

    /** Stages only the email channel for a submitted claim when SMS and WhatsApp are off. */
    @Test
    void stagesOnlyEmailForClaimSubmittedWhenOtherChannelsAreOff() {
        stubChannelSwitches(true, false, false);
        stubBaseCurrency(BASE_CURRENCY);
        stubStaffLookup(APPROVER_ID, approver());
        stubSubmitterName(SUBMITTER_ID, SUBMITTER_NAME);
        stubPublisherSuccess();

        notifier()
                .notifyClaimSubmitted(context(), claimDto(), CLAIM_ACTION_SUBMITTED, List.of(APPROVER_ID))
                .await()
                .indefinitely();

        verify(notificationPublisher)
                .enqueueEmailTemplate(
                        any(NotificationTransaction.class),
                        eq(APPROVER_EMAIL),
                        eq(CLAIM_SUBMITTED_TEMPLATE_NAME),
                        eq(NOTIFICATION_LANGUAGE),
                        argThat((Map<String, Object> parameters) ->
                                parameters.get("claimant_name").equals(SUBMITTER_NAME)
                                        && parameters.get("claim_period").equals(CLAIM_PERIOD)
                                        && parameters.get("amount").equals("SGD 128.00")
                                        && parameters.get("action").equals(CLAIM_ACTION_SUBMITTED)));
        verify(notificationPublisher, never())
                .enqueueSmsTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyMap());
        verify(notificationPublisher, never())
                .enqueueWhatsappTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyMap());
    }

    /** A claim the claimant accepted the rejection of goes out on the dedicated "continued" template. */
    @Test
    void stagesContinuedTemplateWhenClaimantAcceptedTheRejection() {
        stubChannelSwitches(true, false, false);
        stubBaseCurrency(BASE_CURRENCY);
        stubStaffLookup(APPROVER_ID, approver());
        stubSubmitterName(SUBMITTER_ID, SUBMITTER_NAME);
        stubPublisherSuccess();

        notifier()
                .notifyClaimContinued(context(), claimDto(), List.of(APPROVER_ID))
                .await()
                .indefinitely();

        verify(notificationPublisher)
                .enqueueEmailTemplate(
                        any(NotificationTransaction.class),
                        eq(APPROVER_EMAIL),
                        eq("hr_claim_continued_v1"),
                        eq(NOTIFICATION_LANGUAGE),
                        argThat((Map<String, Object> parameters) ->
                                parameters.get("claimant_name").equals(SUBMITTER_NAME)
                                        && parameters.get("claim_period").equals(CLAIM_PERIOD)
                                        && parameters.get("amount").equals("SGD 128.00")
                                        && parameters.get("action").equals("accepted the rejection and continued")));
    }

    /** Stages the resubmitted subject for a claim whose receipt was resubmitted. */
    @Test
    void stagesResubmittedSubjectForClaimResubmitted() {
        stubChannelSwitches(true, false, false);
        stubBaseCurrency(BASE_CURRENCY);
        stubStaffLookup(APPROVER_ID, approver());
        stubSubmitterName(SUBMITTER_ID, SUBMITTER_NAME);
        stubPublisherSuccess();

        notifier()
                .notifyClaimSubmitted(context(), claimDto(), CLAIM_ACTION_RESUBMITTED, List.of(APPROVER_ID))
                .await()
                .indefinitely();

        verify(notificationPublisher)
                .enqueueEmailTemplate(
                        any(NotificationTransaction.class),
                        eq(APPROVER_EMAIL),
                        eq(CLAIM_SUBMITTED_TEMPLATE_NAME),
                        eq(NOTIFICATION_LANGUAGE),
                        argThat((Map<String, Object> parameters) ->
                                parameters.get("action").equals(CLAIM_ACTION_RESUBMITTED)));
    }

    /** Stages every channel for EACH pending approver when the active flow has more than one tier member. */
    @Test
    void stagesEveryChannelForEachPendingApproverUnderAnActiveFlow() {
        String secondApproverId = "APPROVER-2";
        String secondApproverEmail = "approver2@example.com";
        stubChannelSwitches(true, false, false);
        stubBaseCurrency(BASE_CURRENCY);
        stubStaffLookup(APPROVER_ID, approver());
        Staff secondApprover = new Staff();
        secondApprover.setStaffId(secondApproverId);
        secondApprover.setName("Carl");
        secondApprover.setEmailCompany(secondApproverEmail);
        secondApprover.setTelMobile(APPROVER_MOBILE);
        stubStaffLookup(secondApproverId, secondApprover);
        stubSubmitterName(SUBMITTER_ID, SUBMITTER_NAME);
        stubPublisherSuccess();

        notifier()
                .notifyClaimSubmitted(
                        context(), claimDto(), CLAIM_ACTION_SUBMITTED, List.of(APPROVER_ID, secondApproverId))
                .await()
                .indefinitely();

        verify(notificationPublisher)
                .enqueueEmailTemplate(
                        any(NotificationTransaction.class),
                        eq(APPROVER_EMAIL),
                        eq(CLAIM_SUBMITTED_TEMPLATE_NAME),
                        eq(NOTIFICATION_LANGUAGE),
                        anyMap());
        verify(notificationPublisher)
                .enqueueEmailTemplate(
                        any(NotificationTransaction.class),
                        eq(secondApproverEmail),
                        eq(CLAIM_SUBMITTED_TEMPLATE_NAME),
                        eq(NOTIFICATION_LANGUAGE),
                        anyMap());
    }

    /** Stages the approved decision template for the claimant when WhatsApp is the only channel on. */
    @Test
    void stagesApprovedDecisionWhatsappForClaimant() {
        stubChannelSwitches(false, false, true);
        stubBaseCurrency(BASE_CURRENCY);
        stubStaffLookup(CLAIMANT_ID, claimant());
        stubPublisherSuccess();

        notifier()
                .notifyClaimApproved(context(), claim(), List.of(CLAIMANT_ID))
                .await()
                .indefinitely();

        verify(notificationPublisher)
                .enqueueWhatsappTemplate(
                        any(NotificationTransaction.class),
                        eq(APPROVER_MOBILE),
                        eq(CLAIM_DECISION_TEMPLATE_NAME),
                        eq(NOTIFICATION_LANGUAGE),
                        argThat((Map<String, Object> parameters) -> hasApprovedDecision(parameters)));
        verify(notificationPublisher, never())
                .enqueueEmailTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyMap());
        verify(notificationPublisher, never())
                .enqueueSmsTemplate(
                        any(NotificationTransaction.class), anyString(), anyString(), anyString(), anyMap());
    }

    /** On a claim-on-behalf, both the real claimant and the staff who keyed it in get notified. */
    @Test
    void stagesApprovedDecisionForBothClaimantAndEntryStaffOnClaimOnBehalf() {
        String entryStaffId = "ENTRY-1";
        String entryStaffMobile = "+60198765432";
        stubChannelSwitches(false, false, true);
        stubBaseCurrency(BASE_CURRENCY);
        stubStaffLookup(CLAIMANT_ID, claimant());
        Staff entryStaff = new Staff();
        entryStaff.setStaffId(entryStaffId);
        entryStaff.setName("Dana");
        entryStaff.setEmailCompany("entry@example.com");
        entryStaff.setTelMobile(entryStaffMobile);
        stubStaffLookup(entryStaffId, entryStaff);
        stubPublisherSuccess();

        StaffClaim claimOnBehalf = claim();
        claimOnBehalf.setClaimStaffId(CLAIMANT_ID);
        claimOnBehalf.setEntryStaff(entryStaffId);

        notifier()
                .notifyClaimApproved(context(), claimOnBehalf, List.of(CLAIMANT_ID, entryStaffId))
                .await()
                .indefinitely();

        verify(notificationPublisher)
                .enqueueWhatsappTemplate(
                        any(NotificationTransaction.class),
                        eq(APPROVER_MOBILE),
                        eq(CLAIM_DECISION_TEMPLATE_NAME),
                        eq(NOTIFICATION_LANGUAGE),
                        anyMap());
        verify(notificationPublisher)
                .enqueueWhatsappTemplate(
                        any(NotificationTransaction.class),
                        eq(entryStaffMobile),
                        eq(CLAIM_DECISION_TEMPLATE_NAME),
                        eq(NOTIFICATION_LANGUAGE),
                        anyMap());
    }

    /** Skips every channel when the tenant has them all switched off. */
    @Test
    void skipsAllChannelsWhenTenantSwitchesAreOff() {
        stubChannelSwitches(false, false, false);

        notifier()
                .notifyClaimSubmitted(context(), claimDto(), CLAIM_ACTION_SUBMITTED, List.of(APPROVER_ID))
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

    /** Reports whether registry data carries the period and approved outcome. */
    private boolean hasApprovedDecision(Map<String, Object> parameters) {
        return parameters.get("claim_ref").equals(CLAIM_PERIOD)
                && parameters.get("outcome").equals(CLAIM_OUTCOME_APPROVED);
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

    /** Stubs the tenant base currency parameter the amount rendering resolves. */
    private void stubBaseCurrency(String baseCurrency) {
        when(systemParameterService.loadParameter(any(SqlClient.class), eq(SystemParameterService.PARAM_BASE_CURRENCY)))
                .thenReturn(Uni.createFrom().item(baseCurrency));
    }

    /** Stubs each tenant channel switch independently. */
    private void stubChannelSwitches(boolean emailEnabled, boolean smsEnabled, boolean whatsappEnabled) {
        when(systemParameterService.loadParameter(
                        any(SqlClient.class), eq(SystemParameterService.PARAM_NOTIFICATION_EMAIL)))
                .thenReturn(Uni.createFrom().item(Boolean.toString(emailEnabled)));
        when(systemParameterService.loadParameter(
                        any(SqlClient.class), eq(SystemParameterService.PARAM_NOTIFICATION_SMS)))
                .thenReturn(Uni.createFrom().item(Boolean.toString(smsEnabled)));
        when(systemParameterService.loadParameter(
                        any(SqlClient.class), eq(SystemParameterService.PARAM_NOTIFICATION_WHATSAPP)))
                .thenReturn(Uni.createFrom().item(Boolean.toString(whatsappEnabled)));
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
        staff.setName(SUBMITTER_NAME);
        staff.setEmailCompany("claimant@example.com");
        staff.setTelMobile(APPROVER_MOBILE);
        return staff;
    }
}
