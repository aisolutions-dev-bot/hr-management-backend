package com.aisolutions.hrmanagement.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

/**
 * Result returned from the OpenAI receipt OCR endpoint.
 * Fields are nullable — frontend should handle missing values gracefully.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrReceiptResultDTO {

    /** Merchant / vendor name (e.g. "Green on Earth", "McDonald's") */
    private String merchantName;

    /** Receipt / invoice number */
    private String receiptNumber;

    /** Receipt date in ISO format yyyy-MM-dd */
    private String receiptDate;

    /** Total amount paid */
    private BigDecimal receiptAmount;

    /** Raw text extracted by OCR (for debugging / audit) */
    private String rawText;

    /** True if extraction was successful */
    private boolean success = true;

    /** Error message if extraction failed */
    private String errorMessage;
}
