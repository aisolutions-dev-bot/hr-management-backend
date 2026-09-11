package com.aisolutions.hrmanagement.service;

import com.aisolutions.hrmanagement.repository.SystemParameterRepository;
import com.aisolutions.hrmanagement.service.attachment.FtpConfig;
import com.aisolutions.shared.tenancy.CompanyPoolManager;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reads configuration from m07SystemParameters instead of application.properties.
 */
@ApplicationScoped
public class SystemParameterService {

    private static final String DEFAULT_HR_FOLDER = "hr-attachments";

    public static final String PARAM_BASE_CURRENCY = "CURRENCY-BASE";

    public static final String PARAM_NOTIFICATION_EMAIL = "NOTIFICATION-EMAIL";

    public static final String PARAM_NOTIFICATION_SMS = "NOTIFICATION-SMS";

    public static final String PARAM_NOTIFICATION_WHATSAPP = "NOTIFICATION-WHATSAPP";

    private static final List<String> FTP_PARAMS = List.of(
        "ATTACHMENT-MODE",
        "ATTACHMENT-MAIN-URL",
        "ATTACHMENT-PATH-HR",
        "FTP-HOST",
        "FTP-USERNAME",
        "FTP-PASSWORD"
    );

    @Inject
    SystemParameterRepository systemParameterRepository;

    @Inject
    CompanyPoolManager companyPoolManager;

    @Inject
    CurrentUserService currentUserService;

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private volatile FtpConfig cachedFtpConfig;
    private volatile Instant   cacheExpiry = Instant.MIN;

    private volatile String  cachedBaseCurrency;
    private volatile Instant baseCurrencyExpiry = Instant.MIN;

    /**
     * Load FTP configuration from m07SystemParameters.
     */
    public Uni<FtpConfig> loadFtpConfig() {
        if (cachedFtpConfig != null && Instant.now().isBefore(cacheExpiry)) {
            return Uni.createFrom().item(cachedFtpConfig);
        }
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> systemParameterRepository.getParameterMap(pool, FTP_PARAMS))
            .map(params -> {
                String mode = params.get("ATTACHMENT-MODE");
                if (mode == null || mode.isBlank()) {
                    throw new IllegalStateException("ATTACHMENT-MODE not found in m07SystemParameters");
                }
                if (!"FTP".equalsIgnoreCase(mode.trim())) {
                    throw new IllegalStateException(
                        "ATTACHMENT-MODE is '" + mode + "' — only FTP is supported by this module");
                }
                String folder = params.get("ATTACHMENT-PATH-HR");
                if (folder == null || folder.isBlank()) {
                    folder = DEFAULT_HR_FOLDER;
                }
                FtpConfig config = new FtpConfig(
                    require(params, "FTP-HOST"),
                    21,
                    require(params, "FTP-USERNAME"),
                    require(params, "FTP-PASSWORD"),
                    require(params, "ATTACHMENT-MAIN-URL"),
                    folder.trim()
                );
                cachedFtpConfig = config;
                cacheExpiry = Instant.now().plus(CACHE_TTL);
                return config;
            });
    }

    /** Force the next {@link #loadFtpConfig()} call to re-fetch from DB. */
    public void clearFtpConfigCache() {
        cachedFtpConfig = null;
        cacheExpiry     = Instant.MIN;
    }

    /**
     * The currency every claim amount is recorded in, from CURRENCY-BASE.
     */
    public Uni<String> loadBaseCurrency() {
        if (cachedBaseCurrency != null && Instant.now().isBefore(baseCurrencyExpiry)) {
            return Uni.createFrom().item(cachedBaseCurrency);
        }
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> systemParameterRepository.getParameterMap(pool, List.of(PARAM_BASE_CURRENCY)))
            .map(params -> {
                String currency = require(params, PARAM_BASE_CURRENCY);
                cachedBaseCurrency = currency;
                baseCurrencyExpiry = Instant.now().plus(CACHE_TTL);
                return currency;
            });
    }

    /** Force the next {@link #loadBaseCurrency()} call to re-fetch from DB. */
    public void clearBaseCurrencyCache() {
        cachedBaseCurrency = null;
        baseCurrencyExpiry = Instant.MIN;
    }

    /**
     * A single parameter's value, or null when it is absent from m07SystemParameters.
     */
    public Uni<String> loadParameter(String name) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> loadParameter(pool, name));
    }

    /** As {@link #loadParameter(String)} but on an already-resolved tenant pool — safe to run
     *  off the request thread (no request-scoped company lookup). */
    public Uni<String> loadParameter(io.vertx.mutiny.sqlclient.Pool pool, String name) {
        return systemParameterRepository.getParameterMap(pool, List.of(name))
            .map(params -> params.get(name));
    }

    /**
     * Whether leave-notification emails are switched on, from the NOTIFICATION-EMAIL
     * parameter. Uncached so a toggle change takes effect immediately; a missing row or
     * any read failure reads as disabled (fail-safe: never send when unsure).
     */
    public Uni<Boolean> isNotificationEmailEnabled() {
        return loadParameter(PARAM_NOTIFICATION_EMAIL)
            .map(SystemParameterService::truthy)
            .onFailure().recoverWithItem(false);
    }

    /** As {@link #isNotificationEmailEnabled()} but on an already-resolved tenant pool. */
    public Uni<Boolean> isNotificationEmailEnabled(io.vertx.mutiny.sqlclient.Pool pool) {
        return loadParameter(pool, PARAM_NOTIFICATION_EMAIL)
            .map(SystemParameterService::truthy)
            .onFailure().recoverWithItem(false);
    }

    /** As {@link #isNotificationEmailEnabled(io.vertx.mutiny.sqlclient.Pool)} but for NOTIFICATION-SMS. */
    public Uni<Boolean> isNotificationSmsEnabled(io.vertx.mutiny.sqlclient.Pool pool) {
        return loadParameter(pool, PARAM_NOTIFICATION_SMS)
            .map(SystemParameterService::truthy)
            .onFailure().recoverWithItem(false);
    }

    /** As {@link #isNotificationEmailEnabled(io.vertx.mutiny.sqlclient.Pool)} but for NOTIFICATION-WHATSAPP. */
    public Uni<Boolean> isNotificationWhatsappEnabled(io.vertx.mutiny.sqlclient.Pool pool) {
        return loadParameter(pool, PARAM_NOTIFICATION_WHATSAPP)
            .map(SystemParameterService::truthy)
            .onFailure().recoverWithItem(false);
    }

    /** The org-api system-parameters screen writes "TRUE"/"FALSE"; accept common truthy forms too. */
    private static boolean truthy(String value) {
        if (value == null) return false;
        String v = value.trim();
        return v.equalsIgnoreCase("TRUE") || v.equalsIgnoreCase("Y")
            || v.equalsIgnoreCase("YES") || v.equals("1") || v.equalsIgnoreCase("ON");
    }

    private static String require(Map<String, String> params, String key) {
        String v = params.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("System parameter '" + key + "' is not configured in m07SystemParameters");
        }
        return v.trim();
    }
}
