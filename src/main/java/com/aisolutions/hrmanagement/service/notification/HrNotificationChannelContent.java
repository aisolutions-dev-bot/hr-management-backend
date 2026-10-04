package com.aisolutions.hrmanagement.service.notification;

import java.util.Map;

/** Registry template data that the notification dispatcher stages for one staff recipient. */
public interface HrNotificationChannelContent {

    /** Template identity shared by the channel renderers. */
    String templateName();

    /** Language selected by this producer for the notification. */
    String languageCode();

    /** Domain values consumed by the notification registry's channel templates. */
    Map<String, Object> templateParameters();
}
