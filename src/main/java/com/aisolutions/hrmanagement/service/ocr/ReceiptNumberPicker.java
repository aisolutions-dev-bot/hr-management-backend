package com.aisolutions.hrmanagement.service.ocr;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chooses the receipt number from the labelled numbers the AI transcribed off a receipt.
 * Priority: Receipt No, then Invoice No, then Order No, then Reference No.
 */
public final class ReceiptNumberPicker {

    /** Values the AI returns for a number that is not on the receipt. */
    private static final Set<String> ABSENT = Set.of("", "-", "--", "n/a", "na", "none", "null");

    /** Labels that never identify a receipt: table, pump, queue, card, terminal, tax and similar. */
    private static final Pattern EXCLUDED =
            Pattern.compile("\\b(table|pump|queue|cashier|terminal|site|stan|tran|trans|transaction|auth|approval|card|"
                    + "phone|tel|sst|gst|reg|date|time|total|amount|cover)\\b|tax id");

    /** Label wording for each kind of number, in priority order. */
    private static final List<Pattern> KINDS = List.of(
            Pattern.compile("\\b(receipt|rcpt|resit)\\b"),
            Pattern.compile("\\b(inv|invoice|invois)\\b"),
            Pattern.compile("\\border\\b"),
            Pattern.compile("\\b(ref|reference)\\b"));

    private ReceiptNumberPicker() {}

    /** A number with the label printed next to it, both as written on the receipt. */
    public record LabelledNumber(String label, String value) {}

    /**
     * Returns the first usable number in priority order, or null when the receipt carries none.
     *
     * @param numbers every labelled number the AI read from the receipt
     * @return the chosen receipt number, trimmed, or null
     */
    public static String pick(List<LabelledNumber> numbers) {
        if (numbers == null) {
            return null;
        }
        for (Pattern kind : KINDS) {
            for (LabelledNumber number : numbers) {
                String value = clean(number.value());
                if (value != null && hasDigit(value) && matches(kind, number.label())) {
                    return value;
                }
            }
        }
        return null;
    }

    private static boolean matches(Pattern kind, String label) {
        if (label == null) {
            return false;
        }
        String words = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ");
        return !EXCLUDED.matcher(words).find() && kind.matcher(words).find();
    }

    private static boolean hasDigit(String value) {
        return value.chars().anyMatch(Character::isDigit);
    }

    /** Trims, collapses inner whitespace and drops a leading "#" or ":"; null for absent values. */
    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().replaceAll("^[#:\\s]+", "").replaceAll("\\s+", " ");
        return ABSENT.contains(value.toLowerCase(Locale.ROOT)) ? null : value;
    }
}
