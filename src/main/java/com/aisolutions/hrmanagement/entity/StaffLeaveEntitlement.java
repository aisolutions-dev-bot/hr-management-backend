package com.aisolutions.hrmanagement.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Read-only view of a m18StaffLeaveEntitlement row (written by HR Administration's
 * Leave Entitlement screen). One staff member's assigned entitlement for a leave type
 * in a given year. When a row exists it is HR's authoritative number and overrides the
 * leave-type ladder — including for types that carry no ladder band at all.
 */
@Data
@NoArgsConstructor
public class StaffLeaveEntitlement {

    private String staffId;
    private String leaveType;
    private Integer leaveYear;
    private BigDecimal entitledDays;
    private Integer serviceYears;   // snapshot at assignment; null when no join date
    private String source;          // AUTO (from ladder) | MANUAL (HR override)
}
