package com.aisolutions.hrmanagement.service.email;

import com.aisolutions.hrmanagement.config.SmtpConfig;
import com.aisolutions.shared.service.email.EmailConfig;
import com.aisolutions.shared.service.email.EmailService;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Context;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Quarkus wrapper around the shared {@link EmailService}. The blocking SMTP send runs
 * on a worker thread so it never blocks the event loop; failures are swallowed (best-effort).
 */
@ApplicationScoped
public class EmailNotificationService {

    private final EmailService emailService;

    @Inject
    ManagedExecutor managedExecutor;

    @Inject
    Vertx vertx;

    public EmailNotificationService(SmtpConfig smtpConfig) {
        EmailConfig sharedConfig = new EmailConfig();
        sharedConfig.setSenderEmail(smtpConfig.senderEmail());
        sharedConfig.setSmtpPassword(smtpConfig.password());
        this.emailService = new EmailService(sharedConfig, smtpConfig.host(), smtpConfig.port());
    }

    /** Reactive wrapper around the synchronous SMTP send; resolves to false on any failure. */
    public Uni<Boolean> sendReactive(String to, String subject, String htmlBody) {
        Context context = vertx.getOrCreateContext();
        return Uni.createFrom().item(() -> {
                emailService.sendEmail(to, subject, htmlBody);
                return true;
            })
            .runSubscriptionOn(managedExecutor)
            .emitOn(context::runOnContext)
            .onFailure().invoke(Throwable::printStackTrace)
            .onFailure().recoverWithItem(false);
    }
}
