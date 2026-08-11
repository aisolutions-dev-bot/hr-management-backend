package com.aisolutions.hrmanagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Claim header DTO (m18StaffClaims) + its line items.
 *
 * Used for:
 *  - listing claims (header fields only; lines may be null/empty)
 *  - fetching one claim with all its lines populated
 *  - creating a draft (only claimPeriod required)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StaffClaimDTO {

    private Long uniqId;
    private String staffId;
    private String entryStaff;
    private LocalDateTime entryDate;
    private String lastEditStaff;
    private LocalDateTime lastEditDate;

    private String claimPeriod;
    private BigDecimal claimAmount;
    private String status;
    private LocalDateTime submittedDate;
    private BigDecimal approvedAmount;
    /** Sum of REJECTED line amounts — the struck-off total. */
    private BigDecimal rejectedAmount;

    /** Line items — populated when fetching a single claim; may be empty/null in list views. */
    private List<StaffClaimDetailDTO> lines;

    /** Convenience: number of line items (set by the service on read). */
    private Integer lineCount;
}
