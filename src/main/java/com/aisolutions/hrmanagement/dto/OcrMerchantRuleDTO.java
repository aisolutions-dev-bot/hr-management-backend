package com.aisolutions.hrmanagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrMerchantRuleDTO {
    private Long uniqId;
    private String merchantName;
    private String receiptNumberKeyword;
    private String receiptNumberPattern;
    private String dateKeyword;
    private String dateFormat;
    private String amountKeyword;
    private Integer confidence;
    private Integer hitCount;
    private LocalDateTime lastUsed;
    private String entryStaff;
    private LocalDateTime entryDate;
}
