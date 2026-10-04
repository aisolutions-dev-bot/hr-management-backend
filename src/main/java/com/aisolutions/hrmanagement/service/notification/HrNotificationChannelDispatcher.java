package com.aisolutions.hrmanagement.service.notification;

import java.util.Locale;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.hrmanagement.entity.Staff;
import com.aisolutions.hrmanagement.service.SystemParameterService;
import com.aisolutions.shared.notification.NotificationPublisher;
import com.aisolutions.shared.notification.NotificationTransaction;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;
import org.jboss.logging.Logger;

/**
 * Resolves tenant notification switches and stages registry template data for one staff
 * recipient on the shared notification outbox. Delegates switch reads to
 * {@link SystemParameterService} and each channel to {@link NotificationPublisher}.
 */
@ApplicationScoped
public class HrNotificationChannelDispatcher {

    private static final Logger LOG = Logger.getLogger(HrNotificationChannelDispatcher.class);

    private static final String NOTIFICATION_LANGUAGE_ENGLISH = "en";
    private static final Set<String> ENABLED_CHANNEL_VALUES = Set.of("TRUE", "Y", "YES", "1", "ON");

    @Inject
    NotificationPublisher notificationPublisher;

    @Inject
    SystemParameterService systemParameterService;

    /** Loads email through {@link #loadChannelSwitch} and delegates to {@link #loadSmsChannelToggle}. */
    public Uni<HrNotificationChannelToggles> loadChannelToggles(SqlClient client) {
        return loadChannelSwitch(client, SystemParameterService.PARAM_NOTIFICATION_EMAIL)
                .flatMap(emailEnabled -> loadSmsChannelToggle(client, emailEnabled));
    }

    /** Loads SMS and delegates the final channel read to {@link #loadWhatsappChannelToggle}. */
    private Uni<HrNotificationChannelToggles> loadSmsChannelToggle(SqlClient client, boolean emailEnabled) {
        return loadChannelSwitch(client, SystemParameterService.PARAM_NOTIFICATION_SMS)
                .flatMap(smsEnabled -> loadWhatsappChannelToggle(client, emailEnabled, smsEnabled));
    }

    /** Loads WhatsApp and assembles the channel switches after sequential transaction reads. */
    private Uni<HrNotificationChannelToggles> loadWhatsappChannelToggle(
            SqlClient client, boolean emailEnabled, boolean smsEnabled) {
        return loadChannelSwitch(client, SystemParameterService.PARAM_NOTIFICATION_WHATSAPP)
                .map(whatsappEnabled -> new HrNotificationChannelToggles(emailEnabled, smsEnabled, whatsappEnabled));
    }

    /** Reads one channel parameter and delegates its value to {@link #isChannelEnabled}. */
    private Uni<Boolean> loadChannelSwitch(SqlClient client, String parameterName) {
        return systemParameterService.loadParameter(client, parameterName).map(this::isChannelEnabled);
    }

    /**
     * Stages the enabled channels for one staff recipient. Delegates to {@link #stageEmail},
     * {@link #stageSms} and {@link #stageWhatsapp}.
     */
    public Uni<Void> stageStaffChannels(StaffChannelDispatch dispatch) {
        return stageEmail(dispatch).flatMap(ignored -> stageSms(dispatch)).flatMap(ignored -> stageWhatsapp(dispatch));
    }

    /** Carries the transaction, recipient, content and business identity through channel staging. */
    public record StaffChannelDispatch(
            NotificationTransaction context,
            HrNotificationChannelToggles toggles,
            Staff recipientStaff,
            HrNotificationChannelContent content,
            Object businessIdentity) {}

    /** Stages the email channel when the tenant switch is on and a recipient and body exist. */
    private Uni<Void> stageEmail(StaffChannelDispatch dispatch) {
        String recipient = dispatch.recipientStaff().getEmailCompany();
        HrNotificationChannelContent content = dispatch.content();
        if (!dispatch.toggles().emailEnabled()) {
            return recordDispatchSkip(dispatch, "email", "channel_disabled");
        }
        if (isAbsent(recipient)) {
            return recordDispatchSkip(dispatch, "email", "recipient_missing");
        }
        if (isAbsent(content.templateName())) {
            return recordDispatchSkip(dispatch, "email", "content_missing");
        }
        return notificationPublisher.enqueueEmailTemplate(
                dispatch.context(),
                recipient,
                content.templateName(),
                content.languageCode(),
                content.templateParameters());
    }

    /** Stages the SMS channel when the tenant switch is on and a recipient and body exist. */
    private Uni<Void> stageSms(StaffChannelDispatch dispatch) {
        String recipient = dispatch.recipientStaff().getTelMobile();
        HrNotificationChannelContent content = dispatch.content();
        if (!dispatch.toggles().smsEnabled()) {
            return recordDispatchSkip(dispatch, "sms", "channel_disabled");
        }
        if (isAbsent(recipient)) {
            return recordDispatchSkip(dispatch, "sms", "recipient_missing");
        }
        if (isAbsent(content.templateName())) {
            return recordDispatchSkip(dispatch, "sms", "content_missing");
        }
        return notificationPublisher.enqueueSmsTemplate(
                dispatch.context(),
                recipient,
                content.templateName(),
                content.languageCode(),
                content.templateParameters());
    }

    /** Stages the WhatsApp template when the switch is on and a recipient and template exist. */
    private Uni<Void> stageWhatsapp(StaffChannelDispatch dispatch) {
        String recipient = dispatch.recipientStaff().getTelMobile();
        HrNotificationChannelContent content = dispatch.content();
        if (!dispatch.toggles().whatsappEnabled()) {
            return recordDispatchSkip(dispatch, "whatsapp", "channel_disabled");
        }
        if (isAbsent(recipient)) {
            return recordDispatchSkip(dispatch, "whatsapp", "recipient_missing");
        }
        if (isAbsent(content.templateName())) {
            return recordDispatchSkip(dispatch, "whatsapp", "content_missing");
        }
        return notificationPublisher.enqueueWhatsappTemplate(
                dispatch.context(),
                recipient,
                content.templateName(),
                content.languageCode(),
                content.templateParameters());
    }

    /** Records an intentional skip using the dispatch's tenant and business identity. */
    private Uni<Void> recordDispatchSkip(StaffChannelDispatch dispatch, String channel, String reason) {
        LOG.warnf(
                "notification_skipped company_id=%s channel=%s business_identity=%s reason=%s",
                dispatch.context().companyId(), channel, dispatch.businessIdentity(), reason);
        return Uni.createFrom().voidItem();
    }

    /** Recognizes the tenant's documented channel switch values. */
    private boolean isChannelEnabled(String value) {
        return value != null && ENABLED_CHANNEL_VALUES.contains(value.trim().toUpperCase(Locale.ROOT));
    }

    /** Identifies absent recipients and content values. */
    private boolean isAbsent(String value) {
        return value == null || value.isBlank();
    }
}
