package com.aisolutions.hrmanagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StaffClaimDetailDTO {

    private Long uniqId;
    private Long claimId;
    private String entryStaff;
    private LocalDateTime entryDate;
    private String lastEditStaff;
    private LocalDateTime lastEditDate;

    private String staffId;
    private String projectId;
    private String claimType;
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

    // Itemised approval (per-line)
    private String status;
    private String approvedBy;
    private LocalDateTime approvedDate;
    private String rejectReason;

    // Attachment metadata (if uploaded)
    private Long attachmentId;
    private String attachmentPath;
}
