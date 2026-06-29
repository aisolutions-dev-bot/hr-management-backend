package com.aisolutions.hrmanagement.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
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
    public static class FieldAccuracy {
        private Integer merchantName;
        private Integer receiptNumber;
        private Integer receiptDate;
        private Integer receiptAmount;
    }
}
