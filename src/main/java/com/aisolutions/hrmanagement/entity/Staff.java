package com.aisolutions.hrmanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Read-only view of m03Staff, used to resolve a staff member's display name for
 * notification messages ("submitted by {name}"), and — for the leave module — their
 * department (prefilled on a leave application) and join date (drives leave entitlement).
 *
 * m03Staff is a wide legacy table (140+ columns) owned by the staff/payroll side.
 * Only the columns this module reads are mapped — HRMS never writes to it, and
 * mapping the rest would invite an accidental write of stale values.
 */
@Entity
@Table(name = "m03Staff")
@Data
@NoArgsConstructor
public class Staff {

    /** m03Staff's surrogate key. StaffId is the business key (and is uniquely indexed). */
    @Id
    @Column(name = "Code", nullable = false, updatable = false)
    private Long code;

    @Column(name = "StaffId", length = 25)
    private String staffId;

    @Column(name = "Name")
    private String name;

    @Column(name = "Department", length = 25)
    private String department;

    /** Employment start date; the leave entitlement is computed from years of service.
     *  Null for staff whose join date HR has not recorded (~30% of rows). */
    @Column(name = "DateJoin")
    private LocalDateTime dateJoin;

    /** 'Y' when the staff member has a software login (can act on approvals); 'N' = payroll-only. */
    @Column(name = "SystemUser", length = 1)
    private String systemUser;

    /** Employment status: 'O' active, 'T' terminated. */
    @Column(name = "Status", length = 1)
    private String status;
}
