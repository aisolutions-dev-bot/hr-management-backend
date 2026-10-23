package com.aisolutions.hrmanagement.resource.v1.config;

import com.aisolutions.hrmanagement.repository.ClaimSettingsRepository;
import com.aisolutions.hrmanagement.service.CurrencyService;
import com.aisolutions.hrmanagement.service.CurrentUserService;
import com.aisolutions.hrmanagement.service.SystemParameterService;
import com.aisolutions.shared.tenancy.CompanyPoolManager;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Frontend-safe slice of config for the claim module, in one call:
 *
 *   GET /api/v1/claim-config
 *   {
 *     "baseCurrency": "SGD",
 *     "currencies": [ { "code": "MYR", "description": "Ringgit Malaysia", "rate": 0.3125 }, ... ]
 *   }
 *
 * Deliberately NOT a general parameter endpoint: m07SystemParameters also holds
 * secrets (FTP-PASSWORD, CLOUD-STORAGE-*) that must never reach a browser, so the
 * base currency is opted in by hand. A general, IsPublic-gated endpoint
 * belongs in org-api, not here.
 *
 * The currency list feeds the Add Receipt dropdown and drives a live conversion
 * PREVIEW only — the base amount actually stored on a line is recomputed
 * server-side in StaffClaimDetailService, which never trusts a client-sent rate.
 */
@Path("/api/v1/claim-config")
@Produces(MediaType.APPLICATION_JSON)
public class ClaimConfigResource {

    @Inject SystemParameterService systemParameterService;
    @Inject CurrencyService currencyService;
    @Inject ClaimSettingsRepository claimSettingsRepository;
    @Inject CompanyPoolManager companyPoolManager;
    @Inject CurrentUserService currentUserService;

    @GET
    public Uni<Response> getConfig() {
        return systemParameterService.loadBaseCurrency().flatMap(base ->
            currencyService.listCurrenciesWithRates().flatMap(currencies ->
                loadClaimSettings().map(settings -> {
                // HashMap (not Map.of) so a null header rate serialises as null instead of throwing.
                List<Map<String, Object>> currencyList = currencies.stream()
                        .map(c -> {
                            Map<String, Object> m = new HashMap<>();
                            m.put("code", c.code());
                            m.put("description", c.description());
                            m.put("rate", c.headerRate());
                            m.put("months", c.months());
                            return m;
                        })
                        .toList();
                return Response.ok(Map.of(
                        "baseCurrency", base,
                        "currencies", currencyList,
                        "claimTypes", settings.get("claimTypes"),
                        "defaultRules", settings.get("defaultRules"))).build();
            }))
        ).onFailure().recoverWithItem(err -> {
            System.err.println("[ClaimConfig] " + err.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", err.getMessage())).build();
        });
    }

    /** Active claim types (dropdown order) and default rules; empty lists when the settings cannot be read. */
    private Uni<Map<String, Object>> loadClaimSettings() {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> claimSettingsRepository.findActiveClaimTypes(pool).flatMap(types ->
                claimSettingsRepository.findActiveRules(pool).map(rules -> {
                    Map<String, Object> out = new HashMap<>();
                    out.put("claimTypes", types);
                    out.put("defaultRules", rules);
                    return out;
                })))
            .onFailure().recoverWithItem(err -> {
                System.err.println("[ClaimConfig] claim settings unavailable: " + err.getMessage());
                Map<String, Object> out = new HashMap<>();
                out.put("claimTypes", List.of());
                out.put("defaultRules", List.of());
                return out;
            });
    }
}
