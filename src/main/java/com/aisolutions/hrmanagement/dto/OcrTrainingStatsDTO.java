package com.aisolutions.hrmanagement.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class OcrTrainingStatsDTO {

    private Long totalScans;
    private Long totalCorrections;
    private Long merchantAliases;
    private Long merchantRules;
    private Long globalRules;

    private FieldAccuracy fieldAccuracy;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @RegisterForReflection
    public static class FieldAccuracy {
        private Integer merchantName;
        private Integer receiptNumber;
        private Integer receiptDate;
        private Integer receiptAmount;
    }
}
