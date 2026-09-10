package com.aisolutions.hrmanagement.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * SMS gateway settings, bound from the {@code app.sms.*} properties (env-backed).
 * Defaults let the app start without the SMS env vars configured — a send is
 * separately gated by the NOTIFICATION-SMS parameter and by the recipient having a
 * mobile number on file, so a blank URL simply never sends (the provider call fails
 * fast and is swallowed by the caller).
 */
@ConfigMapping(prefix = "app.sms")
public interface SmsConfig {

    @WithDefault("")
    String url();

    @WithDefault("")
    String username();

    @WithDefault("")
    String password();

    @WithDefault("")
    String senderId();
}
