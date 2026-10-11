package com.aisolutions.hrmanagement.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A staff duty roster assignment — maps to m18DutyRoster.
 *
 * Read-only on the HR-management (self-service) side: rows are created and maintained
 * by HR elsewhere (hr-administration-backend). This service only ever queries a staff
 * member's own rows, scoped by the staffId resolved from their verified JWT.
 */
@Entity
@Table(name = "m18DutyRoster")
@Data
@NoArgsConstructor
public class DutyRoster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId", nullable = false, updatable = false)
    private Long uniqId;

    @Column(name = "StaffId", length = 50)
    private String staffId;

    @Column(name = "StaffName", length = 100)
    private String staffName;

    @Column(name = "Department", length = 100)
    private String department;

    @Column(name = "ProjectCode", length = 50)
    private String projectCode;

    @Column(name = "ProjectName", length = 255)
    private String projectName;

    /** Soft reference to the Shift Type master list served by general-settings-backend. */
    @Column(name = "ShiftCode", length = 10)
    private String shiftCode;

    @Column(name = "RosterDate")
    private LocalDate rosterDate;

    @Column(name = "Remarks", length = 255)
    private String remarks;

    /** ACTIVE (default) | others reserved for future use; only ACTIVE rows are shown. */
    @Column(name = "Status", length = 20)
    private String status;

    @Column(name = "EntryStaff", length = 50)
    private String entryStaff;

    @Column(name = "EntryDate")
    private LocalDateTime entryDate;

    @Column(name = "LastEditStaff", length = 50)
    private String lastEditStaff;

    @Column(name = "LastEditDate")
    private LocalDateTime lastEditDate;
}
