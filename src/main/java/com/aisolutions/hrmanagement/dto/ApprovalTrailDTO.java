package com.aisolutions.hrmanagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A leave's approval trail, read from the m07ApprovalAction log against the
 * m07ApprovalManagement flow. {@code flowActive} distinguishes a configured
 * multi-tier flow from the single-approver fallback / legacy records.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalTrailDTO {
    private boolean flowActive;                 // a configured multi-tier flow governs this leave
    private String mode;                        // SEQUENTIAL | PARALLEL | null (fallback)
    private List<ApprovalTrailStepDTO> steps;
    private boolean myTurn;                     // reserved (unused on the applicant side)
    private int approvedCount;                  // ON tiers approved so far (0 when no active flow)
    private int totalTiers;                     // ON tiers in the flow (0 when no active flow)
}
