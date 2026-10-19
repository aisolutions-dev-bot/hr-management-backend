package com.aisolutions.hrmanagement.dto;

import java.math.BigDecimal;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result returned from the OpenAI receipt OCR endpoint.
 * Fields are nullable — frontend should handle missing values gracefully.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
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

    /**
     * Set when the amount was read but cannot be trusted — a fractional total on a
     * currency with no minor unit, or digits that could not be turned into a number.
     * Null when the amount is sound. The frontend shows this alongside the value.
     */
    private String amountWarning;

    /** Tax name as printed (SST, GST, VAT), or NO TAX when the receipt shows none. */
    private String taxType;

    /** Tax rate in percent, 0.00 when there is no tax. */
    private BigDecimal taxRate;

    /** Tax inside the receipt total, in the receipt currency, 0.00 when there is no tax. */
    private BigDecimal taxAmount;

    /** Subtotal before tax and service charge as printed; the total minus tax when it is not printed. */
    private BigDecimal subTotal;

    /** Service charge as printed, in the receipt currency; null when the receipt shows none. */
    private BigDecimal serviceCharge;

    /** Raw text extracted by OCR (for debugging / audit) */
    private String rawText;

    /** True if extraction was successful */
    private boolean success = true;

    /** Error message if extraction failed */
    private String errorMessage;
}
