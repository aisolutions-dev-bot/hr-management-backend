package com.aisolutions.hrmanagement.dto;

import java.time.LocalDateTime;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
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
