package com.aisolutions.hrmanagement.service;

import com.aisolutions.hrmanagement.entity.Currency;
import com.aisolutions.hrmanagement.entity.CurrencyDet;
import com.aisolutions.hrmanagement.repository.CurrencyDetRepository;
import com.aisolutions.hrmanagement.repository.CurrencyRepository;
import com.aisolutions.shared.tenancy.CompanyPoolManager;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CurrencyService {

    private static final Logger LOG = Logger.getLogger(CurrencyService.class);

    @Inject CurrencyRepository currencyRepository;
    @Inject CurrencyDetRepository currencyDetRepository;
    @Inject SystemParameterService systemParameterService;
    @Inject CompanyPoolManager companyPoolManager;
    @Inject CurrentUserService currentUserService;

    public record Converted(String currencyCode, BigDecimal baseAmount, BigDecimal rateUsed) {}

    /** All currencies for the dropdown. */
    public Uni<List<Currency>> listCurrencies() {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> currencyRepository.findAllOrdered(pool));
    }

    public record CurrencyRates(String code, String description,
                                BigDecimal headerRate, List<BigDecimal> months) {}

    public Uni<List<CurrencyRates>> listCurrenciesWithRates() {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> currencyRepository.findAllOrdered(pool).flatMap(currencies ->
                currencyDetRepository.listAll(pool).map(dets -> {
                    Map<Long, CurrencyDet> byRef = new HashMap<>();
                    for (CurrencyDet d : dets) {
                        if (d.getRefUniqId() != null) {
                            byRef.putIfAbsent(d.getRefUniqId(), d);
                        }
                    }
                    return currencies.stream().map(c -> {
                        CurrencyDet det = byRef.get(c.getUniqId());
                        List<BigDecimal> months = new ArrayList<>(12);
                        for (int m = 1; m <= 12; m++) {
                            months.add(det == null ? null : det.rateForMonth(m));
                        }
                        String desc = c.getCurrencyDesc() != null ? c.getCurrencyDesc() : c.getCurrency();
                        return new CurrencyRates(c.getCurrency(), desc, c.getExchangeRate(), months);
                    }).toList();
                })));
    }

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

            if (code.equalsIgnoreCase(base)) {
                return Uni.createFrom().item(
                        new Converted(base, scale(originalAmount), BigDecimal.ONE));
            }

            return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
                .flatMap(pool -> currencyRepository.findByCode(pool, code))
                .flatMap(currency -> {
                    if (currency == null) {
                        throw noRate(code);
                    }
                    return resolveRate(currency, rateDate).map(rate -> {
                        if (rate == null || rate.signum() <= 0) {
                            throw noRate(code);
                        }
                        BigDecimal baseAmount = originalAmount.divide(rate, 2, RoundingMode.HALF_UP);
                        return new Converted(currency.getCurrency(), baseAmount, rate);
                    });
                });
        });
    }

    private static BadRequestException noRate(String code) {
        return new BadRequestException(
            "No exchange rate is set for " + code + ". Ask HR/Finance to add it "
            + "in the currency table before claiming in this currency.");
    }

    private Uni<BigDecimal> resolveRate(Currency currency, LocalDate rateDate) {
        BigDecimal headerRate = currency.getExchangeRate();
        int month = rateDate.getMonthValue();

        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> currencyDetRepository.findByCurrencyId(pool, currency.getUniqId()))
            .map(det -> {
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
