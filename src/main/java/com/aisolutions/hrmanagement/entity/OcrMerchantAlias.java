package com.aisolutions.hrmanagement.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "m20OcrMerchantAlias")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrMerchantAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId", nullable = false, updatable = false)
    private Long uniqId;

    @Column(name = "OcrPattern", length = 200, nullable = false)
    private String ocrPattern;

    @Column(name = "CorrectName", length = 100, nullable = false)
    private String correctName;

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
