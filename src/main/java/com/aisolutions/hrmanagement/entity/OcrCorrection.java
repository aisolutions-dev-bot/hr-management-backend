package com.aisolutions.hrmanagement.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "m20OcrCorrections")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId", nullable = false, updatable = false)
    private Long uniqId;

    @Column(name = "StaffId", length = 25)
    private String staffId;

    @Column(name = "Timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Lob
    @Column(name = "RawText", nullable = false, columnDefinition = "TEXT")
    private String rawText;

    @Column(name = "OcrMerchantName", length = 100)
    private String ocrMerchantName;

    @Column(name = "OcrReceiptNumber", length = 50)
    private String ocrReceiptNumber;

    @Column(name = "OcrReceiptDate", length = 20)
    private String ocrReceiptDate;

    @Column(name = "OcrReceiptAmount", precision = 10, scale = 2)
    private BigDecimal ocrReceiptAmount;

    @Column(name = "CorrectedMerchantName", length = 100)
    private String correctedMerchantName;

    @Column(name = "CorrectedReceiptNumber", length = 50)
    private String correctedReceiptNumber;

    @Column(name = "CorrectedReceiptDate", length = 20)
    private String correctedReceiptDate;

    @Column(name = "CorrectedReceiptAmount", precision = 10, scale = 2)
    private BigDecimal correctedReceiptAmount;

    @Column(name = "MerchantCorrected", nullable = false)
    private Boolean merchantCorrected = false;

    @Column(name = "ReceiptNumberCorrected", nullable = false)
    private Boolean receiptNumberCorrected = false;

    @Column(name = "DateCorrected", nullable = false)
    private Boolean dateCorrected = false;

    @Column(name = "AmountCorrected", nullable = false)
    private Boolean amountCorrected = false;
}
