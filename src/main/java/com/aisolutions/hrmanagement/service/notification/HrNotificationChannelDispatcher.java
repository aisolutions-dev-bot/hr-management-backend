package com.aisolutions.hrmanagement.service.notification;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.hrmanagement.entity.Staff;
import com.aisolutions.hrmanagement.service.SystemParameterService;
import com.aisolutions.shared.notification.NotificationPublisher;
import com.aisolutions.shared.notification.NotificationTransaction;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;

/**
 * Resolves the tenant notification channel switches and stages channel-ready content for one staff
 * recipient on the shared notification outbox. Delegates switch reads to
 * {@link SystemParameterService} and each channel to {@link NotificationPublisher}.
 */
@ApplicationScoped
public class HrNotificationChannelDispatcher {

    private static final String NOTIFICATION_LANGUAGE_ENGLISH = "en";

    @Inject
    NotificationPublisher notificationPublisher;

    @Inject
    SystemParameterService systemParameterService;

    /** Resolves the three tenant channel switches in one sequential read on the transaction. */
    public Uni<HrNotificationChannelToggles> loadChannelToggles(SqlClient client) {
        return systemParameterService
                .isNotificationEmailEnabled(client)
                .flatMap(emailEnabled -> systemParameterService
                        .isNotificationSmsEnabled(client)
                        .flatMap(smsEnabled -> systemParameterService
                                .isNotificationWhatsappEnabled(client)
                                .map(whatsappEnabled ->
                                        new HrNotificationChannelToggles(emailEnabled, smsEnabled, whatsappEnabled))));
    }

    /**
     * Stages the enabled channels for one staff recipient. Delegates to {@link #stageEmail},
     * {@link #stageSms} and {@link #stageWhatsapp}.
     */
    public Uni<Void> stageStaffChannels(
            NotificationTransaction context,
            HrNotificationChannelToggles toggles,
            Staff recipientStaff,
            HrNotificationChannelContent content) {
        return stageEmail(context, toggles, recipientStaff.getEmailCompany(), content)
                .flatMap(ignored -> stageSms(context, toggles, recipientStaff.getTelMobile(), content))
                .flatMap(ignored -> stageWhatsapp(context, toggles, recipientStaff.getTelMobile(), content));
    }

    /** Stages the email channel when the tenant switch is on and a recipient and body exist. */
    private Uni<Void> stageEmail(
            NotificationTransaction context,
            HrNotificationChannelToggles toggles,
            String recipient,
            HrNotificationChannelContent content) {
        if (!toggles.emailEnabled() || isAbsent(recipient) || isAbsent(content.emailBody())) {
            return Uni.createFrom().voidItem();
        }
        return notificationPublisher.enqueueEmail(context, recipient, content.emailSubject(), content.emailBody());
    }

    /** Stages the SMS channel when the tenant switch is on and a recipient and body exist. */
    private Uni<Void> stageSms(
            NotificationTransaction context,
            HrNotificationChannelToggles toggles,
            String recipient,
            HrNotificationChannelContent content) {
        if (!toggles.smsEnabled() || isAbsent(recipient) || isAbsent(content.smsText())) {
            return Uni.createFrom().voidItem();
        }
        return notificationPublisher.enqueueSms(context, recipient, content.smsText());
    }

    /** Stages the WhatsApp template when the switch is on and a recipient and template exist. */
    private Uni<Void> stageWhatsapp(
            NotificationTransaction context,
            HrNotificationChannelToggles toggles,
            String recipient,
            HrNotificationChannelContent content) {
        if (!toggles.whatsappEnabled() || isAbsent(recipient) || isAbsent(content.whatsappTemplateName())) {
            return Uni.createFrom().voidItem();
        }
        return notificationPublisher.enqueueWhatsappTemplate(
                context,
                recipient,
                content.whatsappTemplateName(),
                NOTIFICATION_LANGUAGE_ENGLISH,
                content.whatsappTemplateComponents());
    }

    /** Identifies absent recipients and content values. */
    private boolean isAbsent(String value) {
        return value == null || value.isBlank();
    }
}
