package com.aisolutions.hrmanagement.service.ocr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression suite for the receipt-amount parser: the formats that already work
 * must keep working, and the Vietnamese dot-grouped one that broke must not.
 */
class ReceiptAmountParserTest {

    private static void assertAmount(String expected, String raw, String currency) {
        var r = ReceiptAmountParser.parse(raw, currency);
        assertNotNull(r.amount(), () -> "no amount parsed from \"" + raw + "\"");
        assertEquals(0, new BigDecimal(expected).compareTo(r.amount()),
                () -> "\"" + raw + "\" (" + currency + ") → " + r.amount());
    }

    // ── the bug ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Vietnamese dot grouping is a thousands separator, not a decimal point")
    void vietnameseGrouping() {
        assertAmount("1275750", "1.275.750", "VND");
        assertAmount("20000", "20.000", "VND");
        assertAmount("1275750", "1.275.750", null);   // currency not yet known
    }

    @Test
    @DisplayName("Indonesian rupiah groups the same way")
    void rupiahGrouping() {
        assertAmount("150000", "150.000", "IDR");
        assertAmount("1500000", "1.500.000", "IDR");
    }

    @Test
    @DisplayName("a fractional zero-decimal total is kept but flagged, never 'repaired'")
    void fractionalZeroDecimalIsFlagged() {
        var r = ReceiptAmountParser.parse("12,757.75", "VND");
        assertEquals(0, new BigDecimal("12757.75").compareTo(r.amount()),
                "the misread value must be passed through untouched");
        assertNotNull(r.warning(), "a fractional VND total must be flagged");
        assertTrue(r.warning().contains("VND"));
    }

    @Test
    @DisplayName("a whole-number zero-decimal total is not flagged")
    void wholeZeroDecimalIsClean() {
        assertNull(ReceiptAmountParser.parse("1.275.750", "VND").warning());
        assertNull(ReceiptAmountParser.parse("1275750.00", "VND").warning());
    }

    // ── the currencies that already worked ───────────────────────────────

    @Test
    @DisplayName("MYR/SGD/USD two-decimal formats are unchanged")
    void twoDecimalCurrencies() {
        assertAmount("96.00", "96.00", "MYR");
        assertAmount("1234.50", "1,234.50", "MYR");
        assertAmount("12757.75", "12,757.75", "SGD");
        assertAmount("78", "78", "USD");
        assertAmount("0.61", "0.61", "SGD");
        assertAmount("1234567.89", "1,234,567.89", "USD");
    }

    @Test
    @DisplayName("no warning is raised for currencies that have cents")
    void noWarningForDecimalCurrencies() {
        assertNull(ReceiptAmountParser.parse("1,234.50", "MYR").warning());
        assertNull(ReceiptAmountParser.parse("96.00", "SGD").warning());
    }

    @Test
    @DisplayName("European style, where the comma is the decimal point")
    void europeanStyle() {
        assertAmount("1234.56", "1.234,56", "EUR");
        assertAmount("1275750", "1.275.750", "EUR");
    }

    // ── junk in, no crash out ────────────────────────────────────────────

    @Test
    @DisplayName("currency symbols and spaces are ignored")
    void stripsSymbols() {
        assertAmount("1275750", "VND 1.275.750", "VND");
        assertAmount("96.00", "RM 96.00", "MYR");
        assertAmount("1234.50", "$ 1,234.50", "SGD");
    }

    @Test
    @DisplayName("nothing usable gives a null amount rather than an exception")
    void unreadable() {
        assertNull(ReceiptAmountParser.parse(null, "MYR").amount());
        assertNull(ReceiptAmountParser.parse("", "MYR").amount());
        assertNull(ReceiptAmountParser.parse("null", "MYR").amount());
        assertNull(ReceiptAmountParser.parse("N/A", "MYR").amount());

        var r = ReceiptAmountParser.parse("N/A", "MYR");
        assertNotNull(r.warning(), "an unreadable amount must explain itself");
    }

    @Test
    @DisplayName("a missing amount is silent — it is simply a field not found")
    void missingIsSilent() {
        assertNull(ReceiptAmountParser.parse(null, "MYR").warning());
        assertNull(ReceiptAmountParser.parse("  ", "MYR").warning());
    }

    @Test
    @DisplayName("case and padding on the currency code do not matter")
    void currencyCodeIsNormalised() {
        assertNotNull(ReceiptAmountParser.parse("12,757.75", " vnd ").warning());
    }

    // ── new currencies need no code change ───────────────────────────────

    @Test
    @DisplayName("zero-decimal currencies we have never used are recognised from ISO 4217")
    void zeroDecimalComesFromTheJdk() {
        // Neither is listed anywhere in the parser — the JDK supplies the minor unit.
        assertNotNull(ReceiptAmountParser.parse("1,234.56", "JPY").warning());
        assertNotNull(ReceiptAmountParser.parse("1,234.56", "CLP").warning());
    }

    @Test
    @DisplayName("ordinary new currencies keep their cents without being listed")
    void twoDecimalCurrenciesNeedNoListing() {
        assertNull(ReceiptAmountParser.parse("1,234.56", "THB").warning());
        assertNull(ReceiptAmountParser.parse("1,234.56", "GBP").warning());
        assertNull(ReceiptAmountParser.parse("1,234.56", "AUD").warning());
    }

    @Test
    @DisplayName("an unrecognised currency code parses without a warning rather than failing")
    void unknownCurrencyCodeIsSafe() {
        var r = ReceiptAmountParser.parse("1.275.750", "ZZZ");
        assertEquals(0, new BigDecimal("1275750").compareTo(r.amount()));
        assertNull(r.warning());
    }
}
