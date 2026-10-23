package com.aisolutions.hrmanagement.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class StaffClaimDetailDTO {

    private Long uniqId;
    private Long claimId;
    private String entryStaff;
    private LocalDateTime entryDate;
    private String lastEditStaff;
    private LocalDateTime lastEditDate;

    private String staffId;
    private String projectId;
    private String activityCode;
    private String processCode;
    private String claimType;
    private boolean corporateCreditCard;
    private LocalDateTime claimDate;
    private String claimDescription;

    private String merchantName;
    private String receiptNumber;
    private LocalDateTime receiptDate;
    private BigDecimal receiptAmount;
    private BigDecimal claimAmount;
    private String currency;
    /** Currency the AI/OCR read off the receipt (traceability). */
    private String detectedCurrency;

    private BigDecimal exchangeRate;

    /** Tax read off the receipt, display only: type (SST, GST, VAT or NO TAX), rate in percent, amount. */
    private String taxType;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    /** Receipt amount before tax, in the receipt currency; the server works it out as receipt amount minus tax. */
    private BigDecimal subTotal;
    /** Service charge printed on the receipt, in the receipt currency; null when none. */
    private BigDecimal serviceCharge;

    // Itemised approval (per-line)
    private String status;
    private String approvedBy;
    private LocalDateTime approvedDate;
    private String rejectReason;

    // Attachment metadata (if uploaded)
    private Long attachmentId;
    private String attachmentPath;
}
