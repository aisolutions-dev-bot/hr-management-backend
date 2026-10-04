package com.aisolutions.hrmanagement.service.notification;

import java.util.List;
import java.util.Map;

/** Channel-ready content that the notification dispatcher can stage for one staff recipient. */
public interface HrNotificationChannelContent {

    /** Subject line for the email channel. */
    String emailSubject();

    /** Rendered HTML body for the email channel. */
    String emailBody();

    /** Plain text for the SMS channel. */
    String smsText();

    /** Approved WhatsApp template name for the recipient's tenant. */
    String whatsappTemplateName();

    /** Named body parameters for the WhatsApp template. */
    List<Map<String, Object>> whatsappTemplateComponents();
}
