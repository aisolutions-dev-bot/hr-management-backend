package com.aisolutions.hrmanagement.service;

import com.aisolutions.hrmanagement.entity.Currency;
import com.aisolutions.hrmanagement.repository.CurrencyRepository;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Converts a claim amount from the currency on the receipt to the system base
 * currency (CURRENCY-BASE), using the per-currency rate in m01Currency.
 *
 * Convention is "1 base = rate foreign" (the way rates are naturally quoted, e.g.
 * 1 SGD = 3.20 MYR), so base = original ÷ rate. This matches the task's own example
 * (9.60 / 3.20 = SGD 3.00). The base currency itself is always rate 1, whether or
 * not it has a row. A currency with no row has no known rate — the conversion fails
 * rather than guessing 1, because recording a foreign amount as if it were base
 * (e.g. ¥1000 as $1000) is a silent, unrecoverable error.
 */
@ApplicationScoped
public class CurrencyService {

    @Inject CurrencyRepository currencyRepository;
    @Inject SystemParameterService systemParameterService;

    /**
     * The result of converting an amount to base: the resolved currency code
     * (base when the caller sent none), the base-currency value, and the rate used.
     */
    public record Converted(String currencyCode, BigDecimal baseAmount, BigDecimal rateUsed) {}

    /** All currencies for the dropdown. */
    public Uni<List<Currency>> listCurrencies() {
        return currencyRepository.findAllOrdered();
    }

    /**
     * Convert {@code originalAmount}, expressed in {@code currencyCode}, to base.
     *
     * @param originalAmount the amount on the receipt (receipt currency)
     * @param currencyCode   the receipt's currency; when blank, assumed to be base
     * @return the base-currency amount (2 dp) and the rate that produced it
     */
    public Uni<Converted> toBase(BigDecimal originalAmount, String currencyCode) {
        if (originalAmount == null) {
            return Uni.createFrom().failure(
                    new BadRequestException("Amount is required"));
        }
        return systemParameterService.loadBaseCurrency().flatMap(base -> {
            String code = (currencyCode == null || currencyCode.isBlank())
                    ? base : currencyCode.trim();

            // Same currency as base → no conversion, rate is exactly 1.
            if (code.equalsIgnoreCase(base)) {
                return Uni.createFrom().item(
                        new Converted(base, scale(originalAmount), BigDecimal.ONE));
            }

            return currencyRepository.findByCode(code).map(currency -> {
                if (currency == null || currency.getExchangeRate() == null
                        || currency.getExchangeRate().signum() <= 0) {
                    throw new BadRequestException(
                        "No exchange rate is set for " + code + ". Ask HR/Finance to add it "
                        + "in the currency table before claiming in this currency.");
                }
                BigDecimal rate = currency.getExchangeRate();
                // base = original ÷ rate (1 base = rate foreign). Round to 2 dp in one step.
                BigDecimal baseAmount = originalAmount.divide(rate, 2, RoundingMode.HALF_UP);
                return new Converted(currency.getCurrency(), baseAmount, rate);
            });
        });
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
