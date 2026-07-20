package com.aisolutions.hrmanagement.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Maps to m01Currency — the currencies a claim can be entered in, and the rate
 * that converts each to the system base currency (CURRENCY-BASE).
 *
 * ExchangeRate is read as "1 unit of base = ExchangeRate units of this currency"
 * (e.g. 1 SGD = 3.20 MYR): base = original ÷ rate. The base currency's own row, if
 * present, has rate 1. Rates are keyed against whatever CURRENCY-BASE currently
 * is; changing the base means Finance re-enters every rate.
 */
@Entity
@Table(name = "m01Currency")
@Data
@NoArgsConstructor
public class Currency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId", nullable = false, updatable = false)
    private Long uniqId;

    @Column(name = "Currency", length = 10)
    private String currency;

    @Column(name = "CurrencyDesc", length = 100)
    private String currencyDesc;

    @Column(name = "ExchangeRate", precision = 18, scale = 6)
    private BigDecimal exchangeRate;
}
