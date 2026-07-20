package com.aisolutions.hrmanagement.service;

import com.aisolutions.hrmanagement.repository.SystemParameterRepository;
import com.aisolutions.hrmanagement.service.attachment.FtpConfig;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reads configuration from m07SystemParameters instead of application.properties,
 * so values can be changed in the DB without a redeploy (the HR Railway deployment
 * is read-only for env vars).
 *
 * Required parameters:
 *   ATTACHMENT-MODE     — must be "FTP" (case-insensitive)
 *   ATTACHMENT-MAIN-URL — base URL/path on FTP server (e.g. /<attachment-base>)
 *   FTP-HOST            — FTP server hostname
 *   FTP-USERNAME        — FTP login user
 *   FTP-PASSWORD        — FTP login password
 *   CURRENCY-BASE       — the currency every claim amount is recorded in (e.g. SGD)
 * Optional:
 *   ATTACHMENT-PATH-HR  — module folder for HR files (defaults to "hr-attachments"
 *                          when absent; mirrors jobtasks-attachments convention)
 */
@ApplicationScoped
public class SystemParameterService {

    private static final String DEFAULT_HR_FOLDER = "hr-attachments";

    /**
     * The authoritative base-currency parameter. A second, undocumented
     * SYSTEM-BASE-CURRENCY row also exists in m07SystemParameters with the same
     * value; CURRENCY-BASE is the one this system reads. Do not add a fallback to
     * the other — two live sources will drift.
     */
    public static final String PARAM_BASE_CURRENCY = "CURRENCY-BASE";

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

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private volatile FtpConfig cachedFtpConfig;
    private volatile Instant   cacheExpiry = Instant.MIN;

    private volatile String  cachedBaseCurrency;
    private volatile Instant baseCurrencyExpiry = Instant.MIN;

    /**
     * Load FTP configuration from m07SystemParameters.
     * Cached for 5 minutes — DB changes take effect within 5 minutes, no redeploy needed.
     */
    public Uni<FtpConfig> loadFtpConfig() {
        if (cachedFtpConfig != null && Instant.now().isBefore(cacheExpiry)) {
            return Uni.createFrom().item(cachedFtpConfig);
        }
        return systemParameterRepository.getParameterMap(FTP_PARAMS)
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
     * Cached for 5 minutes, like the FTP config.
     *
     * Claim amounts carry no currency of their own — the value is meaningless
     * without knowing which currency it denominates, so a missing parameter is a
     * configuration error rather than something to guess a default for.
     */
    public Uni<String> loadBaseCurrency() {
        if (cachedBaseCurrency != null && Instant.now().isBefore(baseCurrencyExpiry)) {
            return Uni.createFrom().item(cachedBaseCurrency);
        }
        return systemParameterRepository.getParameterMap(List.of(PARAM_BASE_CURRENCY))
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

    private static String require(Map<String, String> params, String key) {
        String v = params.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("System parameter '" + key + "' is not configured in m07SystemParameters");
        }
        return v.trim();
    }
}
