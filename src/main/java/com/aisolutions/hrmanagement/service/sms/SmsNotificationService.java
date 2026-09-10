package com.aisolutions.hrmanagement.service.sms;

import com.aisolutions.hrmanagement.config.SmsConfig;
import com.aisolutions.shared.service.sms.SmsProperties;
import com.aisolutions.shared.service.sms.SmsService;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Context;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Quarkus wrapper around the shared {@link SmsService}. The blocking HTTP send runs
 * on a worker thread (mirrors {@code EmailNotificationService}) so it never blocks
 * the event loop — this module's fire-and-forget sends run detached from the request,
 * often already on an event-loop thread by then.
 */
@ApplicationScoped
public class SmsNotificationService {

    private final SmsService smsService;

    @Inject
    ManagedExecutor managedExecutor;

    @Inject
    Vertx vertx;

    public SmsNotificationService(SmsConfig smsConfig) {
        SmsProperties props = new SmsProperties();
        props.setUrl(smsConfig.url());
        props.setUsername(smsConfig.username());
        props.setPassword(smsConfig.password());
        props.setSenderId(smsConfig.senderId());
        this.smsService = new SmsService(props);
    }

    /** Reactive wrapper around the synchronous SMS send; resolves to false on any failure
     *  (including a blank/unconfigured URL — the provider call errors fast). */
    public Uni<Boolean> sendReactive(String mobile, String text) {
        Context context = vertx.getOrCreateContext();
        return Uni.createFrom().item(() -> {
                smsService.sendSms(mobile, text);
                return true;
            })
            .runSubscriptionOn(managedExecutor)
            .emitOn(context::runOnContext)
            .onFailure().invoke(Throwable::printStackTrace)
            .onFailure().recoverWithItem(false);
    }
}
