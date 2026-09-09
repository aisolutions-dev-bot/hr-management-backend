package com.aisolutions.hrmanagement.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * SMTP settings for leave-notification emails, bound from the {@code app.email.*}
 * properties (env-backed). Defaults let the app start without the mail env vars
 * configured — a send is separately gated by the NOTIFICATION-EMAIL parameter and
 * by the recipient having an email on file, so blank credentials simply never send.
 */
@ConfigMapping(prefix = "app.email")
public interface SmtpConfig {

    @WithDefault("webmail.aisolutionspl.com")
    String host();

    @WithDefault("587")
    int port();

    @WithDefault("")
    String username();

    @WithDefault("")
    String password();

    @WithDefault("no-reply@aisolutionspl.com")
    String senderEmail();
}
