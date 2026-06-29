package com.aisolutions.hrmanagement.service.dropdown;

import com.aisolutions.hrmanagement.enums.DropdownType;
import com.aisolutions.hrmanagement.repository.DropdownRepository;
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
  
  // Cache: loaded once and reused
  private volatile Map<String, List<?>> cache = new HashMap<>();
  private volatile Set<String> loadedKeys = new HashSet<>();
  private volatile Uni<Map<String, List<?>>> loadingUni = null;

  /**
   * Get all dropdowns from cache (loads LAZILY on first request)
   * Sequential loading to avoid connection pool exhaustion
   */
  public Uni<Map<String, List<?>>> getCachedDropdowns() {
      // If cache is already initialized, return it immediately
      if (isFullyLoaded()) {
          return Uni.createFrom().item(cache);
      }

      // If loading is in progress, return the same Uni to avoid duplicate loads
      if (loadingUni != null) {
          return loadingUni;
      }

      System.out.println("[ProjectMgmt] Loading dropdowns... (sequential)");

      // Create the loading Uni and cache it to prevent duplicate loads
      loadingUni = repository.findAllProjects()
        .onItem().invoke(r -> {
             cache.put(DropdownType.PROJECTS.getKey(), r);
            loadedKeys.add(DropdownType.PROJECTS.getKey());
            //System.out.println("Cached: " + DropdownType.PROJECTS.getKey() + " (" + r.size() + " items)");
        })
        .onItem().invoke(() -> {
            loadingUni = null; 
            System.out.println("[ProjectMgmt] All dropdowns cached successfully");
        })
        .onItem().transform(ignore -> cache)
        .onFailure().invoke(e -> {
            System.err.println("Error caching dropdowns: " + e.getMessage());
            e.printStackTrace();
            loadingUni = null;  // Clear on failure so retry can happen
        });

      return loadingUni;
  }

  private boolean isFullyLoaded() {
    return  loadedKeys.contains(DropdownType.PROJECTS.getKey());
  }

  /**
   * Clear cache (call this if data changes)
   */
  public void clearCache() {
      cache.clear();
      loadedKeys.clear();
      loadingUni = null;
      System.out.println("[ProjectMgmt] Dropdown cache cleared completely");
  }

  /**
   * Clear specific cache entries by key
   */
  public void clearCacheFor(DropdownType... types) {
      for (DropdownType type : types) {
          cache.remove(type.getKey());
          loadedKeys.remove(type.getKey());
      }
      loadingUni = null;
      System.out.println("[ProjectMgmt] Dropdown cache cleared for: " + java.util.Arrays.toString(types));
  }
}