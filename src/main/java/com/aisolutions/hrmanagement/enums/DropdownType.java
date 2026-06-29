package com.aisolutions.hrmanagement.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * All available dropdown types
 */
@Getter
@RequiredArgsConstructor
public enum DropdownType {
    
    // Distinct from Project table
    PROJECTS("projects"),
    OPEN_PROJECTS("openprojects"),
    STATUSES("statuses"),
    CURRENCIES("currencies"),
    COUNTRIES("countries"),
    PROJECT_CLASSES("projectclasses"),
    ;

    private final String key;

    public static DropdownType fromKey(String key) {
        if (key == null) return null;
        for (DropdownType type : values()) {
            if (type.key.equalsIgnoreCase(key)) {
                return type;
            }
        }
        return null;
    }
}