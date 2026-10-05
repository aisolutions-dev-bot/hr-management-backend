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
