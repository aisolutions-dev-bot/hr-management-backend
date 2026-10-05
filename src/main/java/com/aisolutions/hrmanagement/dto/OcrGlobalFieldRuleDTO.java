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
