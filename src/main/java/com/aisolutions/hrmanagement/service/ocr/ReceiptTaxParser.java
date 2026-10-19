package com.aisolutions.hrmanagement.service.ocr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Turns the tax and subtotal the AI read off a receipt into the figures to show.
 * The receipt total already includes tax, so a missing rate or amount is worked out from the other.
 * When the printed rate and the printed amount disagree, the amount wins because it ties to the total.
 * A receipt that shows no usable tax is reported as NO TAX at 0.00.
 */
public final class ReceiptTaxParser {

    /** Tax type reported when the receipt shows no tax. */
    public static final String NO_TAX = "NO TAX";

    private static final String DEFAULT_TYPE = "TAX";
    private static final int TYPE_MAX = 20;
    private static final int RATE_SCALE = 2;
    private static final BigDecimal MAX_RATE = new BigDecimal("30");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal SNAP_TOLERANCE = new BigDecimal("0.2");
    private static final BigDecimal RATE_CONFLICT = new BigDecimal("0.75");
    private static final BigDecimal ROUNDING = new BigDecimal("0.01");

    private ReceiptTaxParser() {}

    /**
     * The tax and subtotal exactly as the AI read them off the receipt; each part may be null.
     *
     * @param type     tax name as printed
     * @param rate     tax rate as printed, for example "6%"
     * @param amount   tax amount as printed
     * @param subTotal subtotal as printed, before tax and any service charge
     * @param serviceCharge service charge as printed
     */
    public record Printed(String type, String rate, String amount, String subTotal, String serviceCharge) {}

    /**
     * @param type     what the receipt calls the tax (SST, GST, VAT), or NO TAX
     * @param rate     tax rate in percent, 0.00 when there is no tax
     * @param amount   tax inside the receipt total, in the receipt currency, 0.00 when there is no tax
     * @param subTotal amount before tax and service charge, the printed subtotal or else what is left of the total
     * @param serviceCharge service charge inside the total, null when the receipt shows none
     */
    public record Tax(String type, BigDecimal rate, BigDecimal amount, BigDecimal subTotal, BigDecimal serviceCharge) {}

    /**
     * @param printed  the tax and subtotal as printed on the receipt
     * @param total    receipt total including tax, may be null
     * @param currency receipt currency, used to read the amounts and round the result
     * @return the figures to show; never null
     */
    public static Tax parse(Printed printed, BigDecimal total, String currency) {
        BigDecimal amount = readAmount(printed.amount(), total, currency);
        BigDecimal rate = readRate(printed.rate());
        BigDecimal service = readAmount(printed.serviceCharge(), total, currency);
        BigDecimal serviceOrZero = service == null ? zero(amountScale(currency)) : service;
        if (total == null || (amount == null && rate == null)) {
            BigDecimal none = zero(amountScale(currency));
            return new Tax(NO_TAX, zero(RATE_SCALE), none,
                    subTotal(printed, total, serviceOrZero, currency), service);
        }
        if (rate == null) {
            rate = impliedRate(amount, total);
        } else if (amount == null) {
            amount = total.multiply(rate).divide(HUNDRED.add(rate), amountScale(currency), RoundingMode.HALF_UP);
        } else if (impliedRate(amount, total).subtract(rate).abs().compareTo(RATE_CONFLICT) > 0) {
            rate = impliedRate(amount, total);
        }
        return new Tax(cleanType(printed.type()), rate.setScale(RATE_SCALE, RoundingMode.HALF_UP), amount,
                subTotal(printed, total, amount.add(serviceOrZero), currency), service);
    }

    private static BigDecimal zero(int scale) {
        return BigDecimal.ZERO.setScale(scale);
    }

    /** The rate that makes this amount the tax inside the total, snapped to a whole rate when very close. */
    private static BigDecimal impliedRate(BigDecimal amount, BigDecimal total) {
        BigDecimal rate = amount.multiply(HUNDRED).divide(total.subtract(amount), 4, RoundingMode.HALF_UP);
        BigDecimal whole = rate.setScale(0, RoundingMode.HALF_UP);
        return rate.subtract(whole).abs().compareTo(SNAP_TOLERANCE) <= 0 ? whole : rate;
    }

    /** A printed amount is usable only when it is positive and smaller than the total. */
    private static BigDecimal readAmount(String raw, BigDecimal total, String currency) {
        ReceiptAmountParser.Result parsed = ReceiptAmountParser.parse(raw, currency);
        BigDecimal amount = parsed.amount();
        if (amount == null || parsed.warning() != null || amount.signum() <= 0) {
            return null;
        }
        if (total != null && amount.compareTo(total) >= 0) {
            return null;
        }
        return amount;
    }

    /** A printed rate is usable only when it is above 0 and no more than 30 percent. */
    private static BigDecimal readRate(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9.,]", "").replace(',', '.');
        try {
            BigDecimal rate = new BigDecimal(digits);
            return rate.signum() > 0 && rate.compareTo(MAX_RATE) <= 0 ? rate : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The printed subtotal when it fits with the charges inside the total, else the total minus the charges. */
    private static BigDecimal subTotal(Printed printed, BigDecimal total, BigDecimal charges, String currency) {
        if (total == null) {
            return null;
        }
        ReceiptAmountParser.Result parsed = ReceiptAmountParser.parse(printed.subTotal(), currency);
        BigDecimal sub = parsed.amount();
        boolean usable = sub != null && parsed.warning() == null && sub.signum() > 0
                && sub.add(charges).compareTo(total.add(ROUNDING)) <= 0;
        return usable ? sub : total.subtract(charges);
    }

    private static int amountScale(String currency) {
        return ReceiptAmountParser.isZeroDecimal(currency) ? 0 : 2;
    }

    private static String cleanType(String type) {
        if (type == null || type.isBlank() || "null".equalsIgnoreCase(type.trim())) {
            return DEFAULT_TYPE;
        }
        String cleaned = type.trim().toUpperCase(Locale.ROOT);
        return cleaned.length() > TYPE_MAX ? cleaned.substring(0, TYPE_MAX) : cleaned;
    }
}
