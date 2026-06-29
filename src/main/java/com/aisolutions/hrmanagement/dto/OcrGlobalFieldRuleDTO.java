package com.aisolutions.hrmanagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrGlobalFieldRuleDTO {
    private Long uniqId;
    private String fieldName;
    private String keyword;
    private String valuePattern;
    private String dateFormat;
    private Integer confidence;
    private Integer hitCount;
    private Integer confirmedByCount;
    private LocalDateTime lastUsed;
    private LocalDateTime entryDate;
}
