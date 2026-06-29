package com.aisolutions.hrmanagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
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
