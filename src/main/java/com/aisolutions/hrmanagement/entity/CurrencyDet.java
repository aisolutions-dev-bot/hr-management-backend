package com.aisolutions.hrmanagement.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Maps to m01CurrencyDet — twelve month-of-year rates per currency
 * (ExRate1 = January … ExRate12 = December), linked to {@link Currency} by RefUniqId.
 *
 * Same direction as the header rate: base = original ÷ rate. The columns are
 * NOT NULL DEFAULT 0, so an unentered month reads as 0.000000, not null.
 */
@Entity
@Table(name = "m01CurrencyDet")
@Data
@NoArgsConstructor
public class CurrencyDet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId", nullable = false, updatable = false)
    private Long uniqId;

    @Column(name = "RefUniqId")
    private Long refUniqId;

    @Column(name = "ExRate1",  precision = 18, scale = 6) private BigDecimal exRate1;
    @Column(name = "ExRate2",  precision = 18, scale = 6) private BigDecimal exRate2;
    @Column(name = "ExRate3",  precision = 18, scale = 6) private BigDecimal exRate3;
    @Column(name = "ExRate4",  precision = 18, scale = 6) private BigDecimal exRate4;
    @Column(name = "ExRate5",  precision = 18, scale = 6) private BigDecimal exRate5;
    @Column(name = "ExRate6",  precision = 18, scale = 6) private BigDecimal exRate6;
    @Column(name = "ExRate7",  precision = 18, scale = 6) private BigDecimal exRate7;
    @Column(name = "ExRate8",  precision = 18, scale = 6) private BigDecimal exRate8;
    @Column(name = "ExRate9",  precision = 18, scale = 6) private BigDecimal exRate9;
    @Column(name = "ExRate10", precision = 18, scale = 6) private BigDecimal exRate10;
    @Column(name = "ExRate11", precision = 18, scale = 6) private BigDecimal exRate11;
    @Column(name = "ExRate12", precision = 18, scale = 6) private BigDecimal exRate12;

    /**
     * The rate for {@code month} (1–12), or null when unset, negative, or out of range.
     * Null means fall back to the header rate — never convert at 1.
     */
    public BigDecimal rateForMonth(int month) {
        BigDecimal rate = switch (month) {
            case 1  -> exRate1;
            case 2  -> exRate2;
            case 3  -> exRate3;
            case 4  -> exRate4;
            case 5  -> exRate5;
            case 6  -> exRate6;
            case 7  -> exRate7;
            case 8  -> exRate8;
            case 9  -> exRate9;
            case 10 -> exRate10;
            case 11 -> exRate11;
            case 12 -> exRate12;
            default -> null;
        };
        return (rate != null && rate.signum() > 0) ? rate : null;
    }
}
