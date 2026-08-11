package com.aisolutions.hrmanagement.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Claim header — maps to m18StaffClaims.
 *
 * One header = one staff member's claim for a period, grouping many
 * {@link StaffClaimDetail} line items (one receipt each).
 *
 * Status lifecycle:
 *   DRAFT → SUBMITTED → APPROVED / PARTIALLY-APPROVED / REJECTED → PAID
 *
 * ClaimAmount    = sum of all line ClaimAmounts (submitted total).
 * ApprovedAmount = sum of APPROVED line ClaimAmounts; frozen at approval
 *                  finalisation, NULL until then.
 */
@Entity
@Table(name = "m18StaffClaims")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StaffClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId", nullable = false, updatable = false)
    private Long uniqId;

    @Column(name = "StaffId", length = 25)
    private String staffId;

    @Column(name = "EntryStaff", length = 25)
    private String entryStaff;

    @Column(name = "EntryDate")
    private LocalDateTime entryDate;

    @Column(name = "LastEditStaff", length = 25)
    private String lastEditStaff;

    @Column(name = "LastEditDate")
    private LocalDateTime lastEditDate;

    @Column(name = "ClaimPeriod", length = 50)
    private String claimPeriod;

    @Column(name = "ClaimAmount", precision = 8, scale = 2)
    private BigDecimal claimAmount;

    /** DRAFT (default) | SUBMITTED | APPROVED | PARTIALLY-APPROVED | REJECTED | PAID. */
    @Column(name = "Status", length = 20)
    private String status;

    @Column(name = "SubmittedDate")
    private LocalDateTime submittedDate;

    @Column(name = "ApprovedAmount", precision = 8, scale = 2)
    private BigDecimal approvedAmount;

    /** Sum of REJECTED line ClaimAmounts — the struck-off total (excludes VOID). */
    @Column(name = "RejectedAmount", precision = 18, scale = 2)
    private BigDecimal rejectedAmount;
}
