package com.aisolutions.hrmanagement.service.ocr;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Set;

/**
 * Turns the amount the AI read off a receipt into a number.
 *
 * The AI is asked to transcribe the total exactly as printed rather than normalise
 * it, because it reads locale separators badly: a Vietnamese receipt printing
 * 1.275.750 came back as 12,757.75 — a hundred times too small, with the last digit
 * invented. Deciding what the separators mean is done here instead, where the rules
 * are explicit and can be tested without calling the AI.
 */
public final class ReceiptAmountParser {

    /**
     * Currencies whose everyday use disagrees with ISO 4217, which is otherwise read
     * from the JDK. Rupiah is formally a 2-decimal currency (sen), but sen have been
     * worthless for decades and no receipt prints them, so a fractional IDR total is
     * a misread rather than a price.
     */
    private static final Set<String> NO_MINOR_UNIT_IN_PRACTICE = Set.of("IDR");

    private ReceiptAmountParser() {}

    /**
     * @param amount  the number read from the receipt, null when nothing usable was returned
     * @param warning what looks wrong, or null when the amount is trustworthy
     */
    public record Result(BigDecimal amount, String warning) {}

    public static Result parse(String raw, String currency) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw.trim())) {
            return new Result(null, null);
        }

        String digits = raw.replaceAll("[^0-9.,]", "");
        if (digits.isEmpty() || digits.replaceAll("[.,]", "").isEmpty()) {
            return new Result(null, "Could not read an amount from \"" + raw
                    + "\" — please enter it manually.");
        }

        boolean zeroDecimal = isZeroDecimal(currency);
        BigDecimal value;
        try {
            value = new BigDecimal(toPlainNumber(digits));
        } catch (NumberFormatException e) {
            return new Result(null, "Could not read an amount from \"" + raw
                    + "\" — please enter it manually.");
        }

        // A zero-decimal currency cannot have cents, so a fraction means the
        // separators were misread and the value is likely 100x out.
        if (zeroDecimal && value.stripTrailingZeros().scale() > 0) {
            return new Result(value, currency.trim().toUpperCase()
                    + " has no decimal places, so \"" + raw + "\" has been misread and the"
                    + " amount is likely far too small. Check it against the receipt"
                    + " before saving.");
        }

        return new Result(value, null);
    }

    /**
     * Rewrites a digits-and-separators string as a plain number, working out which
     * separator (if any) is the decimal point.
     */
    private static String toPlainNumber(String s) {
        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');

        // Both kinds present: the later one is the decimal point and the other
        // groups thousands — 1,234.56 and 1.234,56 are both valid, in opposite locales.
        if (lastDot >= 0 && lastComma >= 0) {
            return keepOnlyDecimal(s, lastDot > lastComma ? '.' : ',');
        }

        char sep = lastDot >= 0 ? '.' : (lastComma >= 0 ? ',' : 0);
        if (sep == 0) return s;

        // Used more than once it can only be grouping — 1.275.750.
        if (countOf(s, sep) > 1) return s.replace(String.valueOf(sep), "");

        // A single separator is ambiguous. Three trailing digits is a thousands group
        // in every locale, so 20.000 is twenty thousand.
        int trailing = s.length() - s.lastIndexOf(sep) - 1;
        if (trailing == 3) return s.replace(String.valueOf(sep), "");

        // Anything else is a decimal point. A zero-decimal currency is deliberately
        // NOT "repaired" here — 12757.75 stays 12757.75 and the caller flags it,
        // because the true total cannot be recovered from a mangled one.
        return keepOnlyDecimal(s, sep);
    }

    /** Drops every separator except {@code decimal}, which becomes a dot. */
    private static String keepOnlyDecimal(String s, char decimal) {
        int decimalAt = s.lastIndexOf(decimal);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') out.append(c);
            else if (i == decimalAt) out.append('.');
        }
        return out.toString();
    }

    private static int countOf(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    /**
     * True when the currency has no usable minor unit, so a fractional total can only
     * be a misread. Taken from the JDK's ISO 4217 data, so a currency added to
     * m01Currency later is covered without editing this class.
     */
    private static boolean isZeroDecimal(String currency) {
        if (currency == null || currency.isBlank()) return false;
        String code = currency.trim().toUpperCase();
        if (NO_MINOR_UNIT_IN_PRACTICE.contains(code)) return true;
        try {
            return Currency.getInstance(code).getDefaultFractionDigits() == 0;
        } catch (IllegalArgumentException e) {
            return false;   // not an ISO code — better to stay quiet than guess
        }
    }
}
