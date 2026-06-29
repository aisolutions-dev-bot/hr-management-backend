package com.aisolutions.hrmanagement.service.dropdown;

import com.aisolutions.hrmanagement.repository.DropdownRepository;
import com.aisolutions.hrmanagement.enums.DropdownType;
import com.aisolutions.hrmanagement.dto.DropdownOptionDTO;

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

  /**
   * Get dropdown options by type
   */
  public Uni<List<DropdownOptionDTO>> getDropdown(DropdownType type) {
      return switch (type) {
        case PROJECTS -> repository.findAllProjects();
        default -> throw new IllegalArgumentException("Unknown dropdown type: " + type);
      };
  }

  /**
   * Get multiple dropdowns by type keys
   * @param typeKeys - List of keys: "staff", "departments", etc.
   */
  public Uni<Map<String, List<DropdownOptionDTO>>> getDropdownsByTypeKeys(List<String> typeKeys) {
      // Always use cached version
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
      return repository.findOpenProjects();
  }

  public void clearDropdownCache() {
      cacheService.clearCache();
  }

  // public Uni<List<DropdownOptionDTO>> getProjects() {
  //     return dropdownRepository.findAllProjects();
  // }

  // public Uni<List<DropdownOptionDTO>> getCurrencies() {
  //     return dropdownRepository.findAllCurrencies();
  // }
}
