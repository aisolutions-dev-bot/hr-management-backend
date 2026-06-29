package com.aisolutions.hrmanagement.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "m20OcrMerchantRule")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrMerchantRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId", nullable = false, updatable = false)
    private Long uniqId;

    @Column(name = "MerchantName", length = 100, nullable = false)
    private String merchantName;

    @Column(name = "ReceiptNumberKeyword", length = 50)
    private String receiptNumberKeyword;

    @Column(name = "ReceiptNumberPattern", length = 100)
    private String receiptNumberPattern;

    @Column(name = "DateKeyword", length = 50)
    private String dateKeyword;

    @Column(name = "DateFormat", length = 20)
    private String dateFormat;

    @Column(name = "AmountKeyword", length = 50)
    private String amountKeyword;

    @Column(name = "Confidence", nullable = false)
    private Integer confidence = 30;

    @Column(name = "HitCount", nullable = false)
    private Integer hitCount = 1;

    @Column(name = "LastUsed", nullable = false)
    private LocalDateTime lastUsed;

    @Column(name = "EntryStaff", length = 25)
    private String entryStaff;

    @Column(name = "EntryDate")
    private LocalDateTime entryDate;
}
