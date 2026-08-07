package com.aisolutions.hrmanagement.service;

import com.aisolutions.hrmanagement.entity.Currency;
import com.aisolutions.hrmanagement.repository.CurrencyDetRepository;
import com.aisolutions.hrmanagement.repository.CurrencyRepository;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Converts a claim amount from the receipt's currency to the base currency
 * (CURRENCY-BASE).
 *
 * Convention is "1 base = rate foreign" (1 SGD = 3.20 MYR), so base = original ÷ rate.
 * A currency with no rate fails the save rather than converting at 1, because
 * recording a foreign amount as if it were base is a silent, unrecoverable error.
 *
 * Rate preference: the m01CurrencyDet rate for the receipt's month, falling back to
 * the m01Currency header rate when that month is unset. The header rate stays the
 * gate on whether a currency is claimable at all.
 */
@ApplicationScoped
public class CurrencyService {

    private static final Logger LOG = Logger.getLogger(CurrencyService.class);

    @Inject CurrencyRepository currencyRepository;
    @Inject CurrencyDetRepository currencyDetRepository;
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
     * @param originalAmount the amount on the receipt (receipt currency)
     * @param currencyCode   the receipt's currency; when blank, assumed to be base
     * @param rateDate       the date the expense was incurred — its month picks the rate
     * @return the base-currency amount (2 dp) and the rate that produced it
     */
    public Uni<Converted> toBase(BigDecimal originalAmount, String currencyCode, LocalDate rateDate) {
        if (originalAmount == null) {
            return Uni.createFrom().failure(
                    new BadRequestException("Amount is required"));
        }
        if (rateDate == null) {
            return Uni.createFrom().failure(
                    new BadRequestException("A date is required to pick the exchange rate"));
        }
        return systemParameterService.loadBaseCurrency().flatMap(base -> {
            String code = (currencyCode == null || currencyCode.isBlank())
                    ? base : currencyCode.trim();

            // Same currency as base → no conversion, rate is exactly 1.
            if (code.equalsIgnoreCase(base)) {
                return Uni.createFrom().item(
                        new Converted(base, scale(originalAmount), BigDecimal.ONE));
            }

            return currencyRepository.findByCode(code).flatMap(currency -> {
                if (currency == null || currency.getExchangeRate() == null
                        || currency.getExchangeRate().signum() <= 0) {
                    throw new BadRequestException(
                        "No exchange rate is set for " + code + ". Ask HR/Finance to add it "
                        + "in the currency table before claiming in this currency.");
                }
                return resolveRate(currency, rateDate).map(rate -> {
                    // base = original ÷ rate (1 base = rate foreign). Round to 2 dp in one step.
                    BigDecimal baseAmount = originalAmount.divide(rate, 2, RoundingMode.HALF_UP);
                    return new Converted(currency.getCurrency(), baseAmount, rate);
                });
            });
        });
    }

    /** Month rate for {@code rateDate} if entered, else the header rate. Never null. */
    private Uni<BigDecimal> resolveRate(Currency currency, LocalDate rateDate) {
        BigDecimal headerRate = currency.getExchangeRate();
        int month = rateDate.getMonthValue();

        return currencyDetRepository.findByCurrencyId(currency.getUniqId()).map(det -> {
            BigDecimal monthRate = (det == null) ? null : det.rateForMonth(month);
            if (monthRate != null) {
                LOG.debugf("[Currency] %s %s: using month rate %s (ExRate%d)",
                        currency.getCurrency(), rateDate, monthRate, month);
                return monthRate;
            }
            LOG.debugf("[Currency] %s %s: no rate for month %d, falling back to header rate %s",
                    currency.getCurrency(), rateDate, month, headerRate);
            return headerRate;
        });
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
