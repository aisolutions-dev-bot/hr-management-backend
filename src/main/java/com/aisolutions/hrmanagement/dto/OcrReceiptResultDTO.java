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

    /**
     * Currency the receipt is in, as an ISO code (e.g. MYR, SGD, USD), or null when
     * the receipt shows no currency or the AI can't tell (e.g. a bare "$"). The
     * frontend defaults a null to the base currency, editable by the user.
     */
    private String currency;

    /** Raw text extracted by OCR (for debugging / audit) */
    private String rawText;

    /** True if extraction was successful */
    private boolean success = true;

    /** Error message if extraction failed */
    private String errorMessage;
}
