package com.aisolutions.hrmanagement.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps to m18StaffClaimsDet.
 *
 * Note: several columns are VARCHAR(25) which is quite tight for descriptions
 * and merchant names. Values are truncated at the service layer before persist.
 */
@Entity
@Table(name = "m18StaffClaimsDet")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StaffClaimDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId", nullable = false, updatable = false)
    private Long uniqId;

    @Column(name = "EntryStaff", length = 25)
    private String entryStaff;

    @Column(name = "EntryDate")
    private LocalDateTime entryDate;

    @Column(name = "LastEditStaff", length = 25)
    private String lastEditStaff;

    @Column(name = "LastEditDate")
    private LocalDateTime lastEditDate;

    /** FK → m17StaffClaims.UniqId (the claim header this line belongs to). */
    @Column(name = "ClaimId")
    private Long claimId;

    @Column(name = "StaffId", length = 25)
    private String staffId;

    @Column(name = "ProjectId", length = 25)
    private String projectId;

    @Column(name = "ClaimType", length = 25)
    private String claimType;

    @Column(name = "ClaimDate")
    private LocalDateTime claimDate;

    @Column(name = "ClaimDescription", length = 100)
    private String claimDescription;

    @Column(name = "MerchantName", length = 25)
    private String merchantName;

    @Column(name = "ReceiptNumber", length = 25)
    private String receiptNumber;

    @Column(name = "ReceiptDate")
    private LocalDateTime receiptDate;

    @Column(name = "ReceiptAmount", precision = 8, scale = 2)
    private BigDecimal receiptAmount;

    @Column(name = "ClaimAmount", precision = 8, scale = 2)
    private BigDecimal claimAmount;

    @Column(name = "Currency", length = 10)
    private String currency;

    @Column(name = "ExchangeRate", precision = 18, scale = 10)
    private BigDecimal exchangeRate;

    // ── Itemised approval (per-line) ──
    /** PENDING (default) | APPROVED | REJECTED. */
    @Column(name = "Status", length = 20)
    private String status;

    @Column(name = "ApprovedBy", length = 25)
    private String approvedBy;

    @Column(name = "ApprovedDate")
    private LocalDateTime approvedDate;

    @Column(name = "RejectReason", length = 255)
    private String rejectReason;
}
