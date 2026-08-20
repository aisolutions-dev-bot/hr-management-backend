package com.aisolutions.hrmanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A staff leave application — maps to m18LeaveApplications.
 *
 * Wizard flow (staff side):
 *   Step 1 particulars → Step 2 leave details → Step 3 period → Step 4 approver → submit.
 *
 * A row is either an APPLY (a new leave request) or a CANCEL (a request to cancel an
 * already-approved leave, referenced by {@link #cancelRefId}).
 *
 * Status lifecycle: SUBMITTED → APPROVED / REJECTED. A CANCEL that is APPROVED flips the
 * referenced APPLY row to CANCELLED, returning its days to the balance.
 */
@Entity
@Table(name = "m18LeaveApplications")
@Data
@NoArgsConstructor
public class LeaveApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId", nullable = false, updatable = false)
    private Long uniqId;

    // ── Step 1 — Employee's particulars ──
    @Column(name = "StaffId", length = 25)
    private String staffId;

    /** Applicant's display name, snapshotted at apply time. */
    @Column(name = "StaffName")
    private String staffName;

    @Column(name = "Department", length = 25)
    private String department;

    /** Step-1 date (defaults to today, editable). */
    @Column(name = "ApplicationDate")
    private LocalDateTime applicationDate;

    // ── Step 2 — Leave details ──
    /** APPLY | CANCEL ("I wish to"). */
    @Column(name = "LeaveAction", length = 10)
    private String leaveAction;

    /** m01LeaveType.LeaveType code (e.g. "AL"). */
    @Column(name = "LeaveType", length = 5)
    private String leaveType;

    @Column(name = "Remarks", length = 500)
    private String remarks;

    // ── Step 3 — Leave period (APPLY only) ──
    @Column(name = "FromDate")
    private LocalDate fromDate;

    @Column(name = "ToDate")
    private LocalDate toDate;

    /** AM | PM | null (full day). A half-day counts as 0.5. */
    @Column(name = "HalfDayPeriod", length = 5)
    private String halfDayPeriod;

    /** Working days taken (0.5 steps). */
    @Column(name = "TotalDays", precision = 5, scale = 1)
    private BigDecimal totalDays;

    /** The APPLY row this CANCEL targets (m18LeaveApplications.UniqId); null for APPLY. */
    @Column(name = "CancelRefId")
    private Long cancelRefId;

    // ── Step 4 — Department manager ──
    @Column(name = "ApproverStaffId", length = 25)
    private String approverStaffId;

    // ── Approval lifecycle ──
    /** SUBMITTED (default) | APPROVED | REJECTED | CANCELLED. */
    @Column(name = "Status", length = 20)
    private String status;

    @Column(name = "ApprovedBy", length = 25)
    private String approvedBy;

    @Column(name = "ApprovedDate")
    private LocalDateTime approvedDate;

    @Column(name = "RejectReason", length = 255)
    private String rejectReason;

    // ── Audit (SGT) ──
    @Column(name = "EntryStaff", length = 25)
    private String entryStaff;

    @Column(name = "EntryDate")
    private LocalDateTime entryDate;

    @Column(name = "LastEditStaff", length = 25)
    private String lastEditStaff;

    @Column(name = "LastEditDate")
    private LocalDateTime lastEditDate;
}
