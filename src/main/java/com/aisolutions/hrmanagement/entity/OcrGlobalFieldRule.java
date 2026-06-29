package com.aisolutions.hrmanagement.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "m20OcrGlobalFieldRule")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrGlobalFieldRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId", nullable = false, updatable = false)
    private Long uniqId;

    @Column(name = "FieldName", length = 25, nullable = false)
    private String fieldName;

    @Column(name = "Keyword", length = 100)
    private String keyword;

    @Column(name = "ValuePattern", length = 100)
    private String valuePattern;

    @Column(name = "DateFormat", length = 20)
    private String dateFormat;

    @Column(name = "Confidence", nullable = false)
    private Integer confidence = 30;

    @Column(name = "HitCount", nullable = false)
    private Integer hitCount = 1;

    @Column(name = "ConfirmedByCount", nullable = false)
    private Integer confirmedByCount = 1;

    @Column(name = "LastUsed", nullable = false)
    private LocalDateTime lastUsed;

    @Column(name = "EntryDate")
    private LocalDateTime entryDate;
}
