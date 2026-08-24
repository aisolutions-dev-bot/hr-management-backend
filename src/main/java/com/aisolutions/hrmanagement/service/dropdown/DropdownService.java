package com.aisolutions.hrmanagement.service.dropdown;

import com.aisolutions.hrmanagement.enums.DropdownType;
import com.aisolutions.hrmanagement.repository.DropdownRepository;
import com.aisolutions.hrmanagement.dto.DropdownOptionDTO;
import com.aisolutions.shared.tenancy.CompanyPoolManager;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

@ApplicationScoped
public class DropdownService {
    
  @Inject
  DropdownRepository repository;

  @Inject
  DropdownCacheService cacheService;

  @Inject
  CompanyPoolManager companyPoolManager;

  @Inject
  com.aisolutions.hrmanagement.service.CurrentUserService currentUserService;

  /**
   * Get dropdown options by type
   */
  public Uni<List<DropdownOptionDTO>> getDropdown(DropdownType type) {
      return switch (type) {
        case PROJECTS -> companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> repository.findAllProjects(pool));
        default -> throw new IllegalArgumentException("Unknown dropdown type: " + type);
      };
  }

  /**
   * Get multiple dropdowns by type keys
   */
  public Uni<Map<String, List<DropdownOptionDTO>>> getDropdownsByTypeKeys(List<String> typeKeys) {
      return cacheService.getCachedDropdowns()
          .onItem().transform(allDropdowns -> {
              Map<String, List<DropdownOptionDTO>> result = new HashMap<>();
              for (String typeKey : typeKeys) {
                  List<?> data = allDropdowns.get(typeKey);
                  if (data != null) {
                      @SuppressWarnings("unchecked")
                      List<DropdownOptionDTO> typedList = (List<DropdownOptionDTO>) data;
                      result.put(typeKey, typedList);
                  }
              }
              return result;
          });
  }

  /**
   * Get Open Projects for dropdown
   */
  public Uni<List<DropdownOptionDTO>> getOpenProjects() {
      return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
          .flatMap(pool -> repository.findOpenProjects(pool));
  }

  public void clearDropdownCache() {
      cacheService.clearCache();
  }
}
