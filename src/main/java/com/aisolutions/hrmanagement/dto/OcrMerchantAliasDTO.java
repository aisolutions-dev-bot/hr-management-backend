package com.aisolutions.hrmanagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrMerchantAliasDTO {
    private Long uniqId;
    private String ocrPattern;
    private String correctName;
    private Integer confidence;
    private Integer hitCount;
    private LocalDateTime lastUsed;
    private String entryStaff;
    private LocalDateTime entryDate;
}
