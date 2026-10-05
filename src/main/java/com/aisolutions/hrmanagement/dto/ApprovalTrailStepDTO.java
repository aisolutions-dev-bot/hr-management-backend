package com.aisolutions.hrmanagement.dto;

import java.time.LocalDateTime;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One node in a leave's approval trail: a configured tier (or the single
 * fallback approver). {@code status} is APPROVED / REJECTED / PENDING.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class ApprovalTrailStepDTO {
    private Integer level; // tier level (null for a single fallback step)
    private String approverStaffId;
    private String approverName;
    private String approverDept; // approver's department (trail subline; may be null)
    private String status; // APPROVED | REJECTED | PENDING
    private String reason; // reject reason, when rejected
    private LocalDateTime actionDate; // when acted (null while pending)
}
