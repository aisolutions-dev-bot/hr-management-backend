package com.aisolutions.hrmanagement.service.notification;

/**
 * Tenant delivery switches resolved from the notification system parameters for one event.
 *
 * @param emailEnabled whether the email channel is switched on
 * @param smsEnabled whether the SMS channel is switched on
 * @param whatsappEnabled whether the WhatsApp channel is switched on
 */
public record HrNotificationChannelToggles(boolean emailEnabled, boolean smsEnabled, boolean whatsappEnabled) {

    /** Reports whether any channel needs staging for this event. */
    public boolean anyEnabled() {
        return emailEnabled || smsEnabled || whatsappEnabled;
    }
}
