package com.aisolutions.hrmanagement.service.whatsapp;

import com.aisolutions.shared.service.whatsapp.MetaWhatsappProperties;
import com.aisolutions.shared.service.whatsapp.MetaWhatsappService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Produces the shared {@link MetaWhatsappService} from the {@code whatsapp.meta.*}
 * properties. The phone-number-id and access-token default to blank so the app still
 * boots with no WhatsApp env vars configured — a send is separately gated by the
 * NOTIFICATION-WHATSAPP parameter and by the recipient having a mobile number on file,
 * so without real credentials the Meta call simply fails fast and is swallowed.
 */
@Singleton
public class MetaWhatsappServiceProducer {

    @Produces
    @ApplicationScoped
    public MetaWhatsappService metaWhatsappService(
            @ConfigProperty(name = "whatsapp.meta.phone-number-id", defaultValue = "") String phoneNumberId,
            @ConfigProperty(name = "whatsapp.meta.access-token", defaultValue = "") String accessToken,
            @ConfigProperty(name = "whatsapp.meta.api-version", defaultValue = "v25.0") String apiVersion) {

        MetaWhatsappProperties properties = new MetaWhatsappProperties(phoneNumberId, accessToken, apiVersion);
        return new MetaWhatsappService(properties);
    }
}
