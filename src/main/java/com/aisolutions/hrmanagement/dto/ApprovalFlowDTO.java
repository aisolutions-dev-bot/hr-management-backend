package com.aisolutions.hrmanagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The approval flow shown to the applicant on the apply form. When {@code active}
 * is true the leave routes through the configured tiers (the applicant does not
 * pick an approver); otherwise the applicant chooses their own approver.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalFlowDTO {
    private boolean active;
    private String mode;            // SEQUENTIAL | PARALLEL | null
    private List<Tier> tiers;       // ordered ON tiers (empty when inactive)

    /** One tier of the chain: its level and approver. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Tier {
        private int level;
        private String approverStaffId;
        private String approverName;
        private String approverDept;
    }
}
