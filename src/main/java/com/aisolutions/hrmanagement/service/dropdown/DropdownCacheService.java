package com.aisolutions.hrmanagement.service.dropdown;

import com.aisolutions.hrmanagement.enums.DropdownType;
import com.aisolutions.hrmanagement.repository.DropdownRepository;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class DropdownCacheService {
    
  @Inject
  DropdownRepository repository;

  @Inject
  CompanyPoolManager companyPoolManager;

  @Inject
  com.aisolutions.hrmanagement.service.CurrentUserService currentUserService;
  
  // Cache: loaded once and reused
  private volatile Map<String, List<?>> cache = new HashMap<>();
  private volatile Set<String> loadedKeys = new HashSet<>();
  private volatile Uni<Map<String, List<?>>> loadingUni = null;

  /**
   * Get all dropdowns from cache (loads LAZILY on first request)
   */
  public Uni<Map<String, List<?>>> getCachedDropdowns() {
      if (isFullyLoaded()) {
          return Uni.createFrom().item(cache);
      }

      if (loadingUni != null) {
          return loadingUni;
      }

      System.out.println("[ProjectMgmt] Loading dropdowns... (sequential)");

      loadingUni = companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
        .flatMap(pool -> repository.findAllProjects(pool))
        .onItem().invoke(r -> {
             cache.put(DropdownType.PROJECTS.getKey(), r);
            loadedKeys.add(DropdownType.PROJECTS.getKey());
        })
        .onItem().invoke(() -> {
            loadingUni = null; 
            System.out.println("[ProjectMgmt] All dropdowns cached successfully");
        })
        .onItem().transform(ignore -> cache)
        .onFailure().invoke(e -> {
            System.err.println("Error caching dropdowns: " + e.getMessage());
            e.printStackTrace();
            loadingUni = null;
        });

      return loadingUni;
  }

  private boolean isFullyLoaded() {
    return  loadedKeys.contains(DropdownType.PROJECTS.getKey());
  }

  public void clearCache() {
      cache.clear();
      loadedKeys.clear();
      loadingUni = null;
      System.out.println("[ProjectMgmt] Dropdown cache cleared completely");
  }

  public void clearCacheFor(DropdownType... types) {
      for (DropdownType type : types) {
          cache.remove(type.getKey());
          loadedKeys.remove(type.getKey());
      }
      loadingUni = null;
      System.out.println("[ProjectMgmt] Dropdown cache cleared for: " + java.util.Arrays.toString(types));
  }
}
