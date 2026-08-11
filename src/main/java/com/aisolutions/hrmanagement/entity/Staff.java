package com.aisolutions.hrmanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only view of m03Staff, used to resolve a staff member's display name for
 * notification messages ("submitted by {name}").
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
}
