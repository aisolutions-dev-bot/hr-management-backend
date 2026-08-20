package com.aisolutions.hrmanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only view of m01LeaveTypeEntitlement (owned by General Settings). Each row is
 * one entitlement band for a leave type: a staff member with at least {@code yearOfService}
 * completed years is entitled to {@code daysOfLeave} days per year. Bands are thresholds —
 * the highest band whose YearOfService ≤ the staff's service years applies.
 *
 * e.g. AL: 1yr→7, 2yr→8, 3yr→9 … → 2.5 years of service = the 2-year band = 8 days.
 */
@Entity
@Table(name = "m01LeaveTypeEntitlement")
@Data
@NoArgsConstructor
public class LeaveTypeEntitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId", nullable = false, updatable = false)
    private Long uniqId;

    @Column(name = "LeaveType", length = 5)
    private String leaveType;

    /** Completed-years threshold for this band. */
    @Column(name = "YearOfService")
    private Integer yearOfService;

    /** Days of leave granted per year at this band. */
    @Column(name = "DaysOfLeave")
    private Integer daysOfLeave;
}
