package com.aisolutions.hrmanagement.service.dropdown;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.hrmanagement.enums.DropdownType;
import com.aisolutions.hrmanagement.repository.DropdownRepository;
import com.aisolutions.hrmanagement.service.CurrentUserService;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

/**
 * Caches dropdown data, routing through {@link CompanyPoolManager} for per-company
 * database resolution. The cache is kept per company: every company has its own
 * database, so one shared cache would serve the first company's rows to all the others.
 */
@ApplicationScoped
public class DropdownCacheService {

    private static final Logger LOG = Logger.getLogger(DropdownCacheService.class);

    /** The cached dropdowns and in-flight load of a single company's database. */
    private static final class TenantCache {
        private final Map<String, List<?>> values = new ConcurrentHashMap<>();
        private volatile Uni<Map<String, List<?>>> loading;
    }

    @Inject
    DropdownRepository repository;

    @Inject
    CompanyPoolManager companyPoolManager;

    @Inject
    CurrentUserService currentUserService;

    /** Keyed by the resolving companyId; blank = the default database. */
    private final Map<String, TenantCache> byCompany = new ConcurrentHashMap<>();

    private TenantCache currentTenant() {
        String companyId = currentUserService.getCurrentCompanyId();
        return byCompany.computeIfAbsent(companyId == null ? "" : companyId, key -> new TenantCache());
    }

    /**
     * Get all dropdowns for the caller's company from cache (loads LAZILY on first request).
     */
    public Uni<Map<String, List<?>>> getCachedDropdowns() {
        TenantCache tenant = currentTenant();
        if (isFullyLoaded(tenant)) {
            return Uni.createFrom().item(tenant.values);
        }

        if (tenant.loading != null) {
            return tenant.loading;
        }

        LOG.debug("Loading dropdowns...");

        tenant.loading = companyPoolManager
                .poolFor(currentUserService.getCurrentCompanyId())
                .flatMap(pool -> repository.findAllProjects(pool))
                .onItem()
                .invoke(rows -> tenant.values.put(DropdownType.PROJECTS.getKey(), rows == null ? List.of() : rows))
                .onItem()
                .invoke(() -> {
                    tenant.loading = null;
                    LOG.debug("All dropdowns cached successfully");
                })
                .onItem()
                .transform(ignore -> tenant.values)
                .onFailure()
                .invoke(e -> {
                    LOG.error("Error caching dropdowns", e);
                    tenant.loading = null;
                });

        return tenant.loading;
    }

    private boolean isFullyLoaded(TenantCache tenant) {
        return tenant.values.containsKey(DropdownType.PROJECTS.getKey());
    }

    /**
     * Clear the caller's company cache (call this if data changes)
     */
    public void clearCache() {
        TenantCache tenant = currentTenant();
        tenant.values.clear();
        tenant.loading = null;
        LOG.debug("Dropdown cache cleared completely");
    }

    /**
     * Clear specific cache entries by key, for the caller's company
     */
    public void clearCacheFor(DropdownType... types) {
        TenantCache tenant = currentTenant();
        for (DropdownType type : types) {
            tenant.values.remove(type.getKey());
        }
        tenant.loading = null;
        LOG.debug("Dropdown cache cleared for: " + Arrays.toString(types));
    }
}
