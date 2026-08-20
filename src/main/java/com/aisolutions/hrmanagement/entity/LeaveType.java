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
 * Read-only view of m01LeaveType (owned by General Settings). HRMS reads it to
 * populate the Leave Type dropdown on a leave application; it never writes here.
 *
 * LeaveType (the varchar code, e.g. "AL") is the business key that
 * {@link LeaveApplication#getLeaveType()} and {@link LeaveTypeEntitlement} link to.
 */
@Entity
@Table(name = "m01LeaveType")
@Data
@NoArgsConstructor
public class LeaveType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId", nullable = false, updatable = false)
    private Long uniqId;

    @Column(name = "LeaveType", length = 5)
    private String leaveType;

    @Column(name = "Description", length = 30)
    private String description;
}
