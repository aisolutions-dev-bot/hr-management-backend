package com.aisolutions.hrmanagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecordCorrectionRequestDTO {

    /** The raw OCR output as originally read from the receipt image. */
    private OcrReceiptResultDTO ocrResult;

    /** The final values the user actually saved. */
    private String correctedMerchantName;
    private String correctedReceiptNumber;
    private String correctedReceiptDate;
    private BigDecimal correctedReceiptAmount;
}
