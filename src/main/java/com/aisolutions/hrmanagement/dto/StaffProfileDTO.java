package com.aisolutions.hrmanagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The logged-in staff member's particulars used to prefill Step 1 of the leave wizard
 * (Name + Department are locked fields). Resolved from m03Staff by the current user's id.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StaffProfileDTO {
    private String staffId;
    private String name;
    private String department;
}
