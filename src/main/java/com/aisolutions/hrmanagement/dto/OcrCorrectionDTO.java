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
public class OcrCorrectionDTO {
    private Long uniqId;
    private String staffId;
    private LocalDateTime timestamp;
    private String rawText;

    private String ocrMerchantName;
    private String ocrReceiptNumber;
    private String ocrReceiptDate;
    private BigDecimal ocrReceiptAmount;

    private String correctedMerchantName;
    private String correctedReceiptNumber;
    private String correctedReceiptDate;
    private BigDecimal correctedReceiptAmount;

    private Boolean merchantCorrected;
    private Boolean receiptNumberCorrected;
    private Boolean dateCorrected;
    private Boolean amountCorrected;
}
