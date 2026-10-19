package com.aisolutions.hrmanagement.service.ocr;

import java.math.BigDecimal;

import com.aisolutions.hrmanagement.service.ocr.ReceiptTaxParser.Printed;
import com.aisolutions.hrmanagement.service.ocr.ReceiptTaxParser.Tax;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Covers a printed tax and subtotal, a worked-out rate or amount, rounding per currency and the no-tax case. */
class ReceiptTaxParserTest {

    private static final String MYR = "MYR";
    private static final String SGD = "SGD";
    private static final String SST = "SST";
    private static final String GST = "GST";
    private static final String NO_TAX = "NO TAX";
    private static final String SIX = "6.00";
    private static final String SIX_PERCENT = "6%";
    private static final String NINE = "9.00";
    private static final String TEN = "10.00";
    private static final String EIGHT_EIGHTY = "8.80";
    private static final String HUNDRED = "100.00";
    private static final BigDecimal TOTAL = new BigDecimal("106.00");
    private static final BigDecimal SGD_TOTAL = new BigDecimal("109.00");

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static Printed p(String type, String rate, String amount) {
        return new Printed(type, rate, amount, null, null);
    }

    @Test
    @DisplayName("printed rate and amount are used as they are")
    void printedRateAndAmount() {
        Tax tax = ReceiptTaxParser.parse(p(SST, SIX_PERCENT, SIX), TOTAL, MYR);

        assertEquals(SST, tax.type());
        assertEquals(d(SIX), tax.rate());
        assertEquals(d(SIX), tax.amount());
    }

    @Test
    @DisplayName("amount is worked out from the rate because the total includes tax")
    void amountFromRate() {
        Tax tax = ReceiptTaxParser.parse(p("sst", "6", null), TOTAL, MYR);

        assertEquals(SST, tax.type());
        assertEquals(d(SIX), tax.amount());
    }

    @Test
    @DisplayName("rate is worked out from the amount and keeps its decimals when far from a whole rate")
    void rateFromAmount() {
        Tax tax = ReceiptTaxParser.parse(p(GST, null, "8.89"), d("150.00"), SGD);

        assertEquals(GST, tax.type());
        assertEquals(d("6.30"), tax.rate());
        assertEquals(d("8.89"), tax.amount());
    }

    @Test
    @DisplayName("a rate within rounding distance of a whole number becomes that number")
    void rateIsSnapped() {
        Tax tax = ReceiptTaxParser.parse(p("VAT", null, "9.82"), d("109.00"), SGD);

        assertEquals(d(TEN), tax.rate());
    }

    @Test
    @DisplayName("a printed rate that contradicts the printed amount loses to the amount")
    void amountBeatsConflictingRate() {
        Tax tax = ReceiptTaxParser.parse(p(GST, "7%", NINE), SGD_TOTAL, SGD);

        assertEquals(d(NINE), tax.rate());
        assertEquals(d(NINE), tax.amount());
    }

    @Test
    @DisplayName("a printed rate and amount that agree are left alone")
    void agreeingRateAndAmount() {
        Tax tax = ReceiptTaxParser.parse(p(GST, "9%", NINE), SGD_TOTAL, SGD);

        assertEquals(d(NINE), tax.rate());
    }

    @Test
    @DisplayName("a currency without decimals gives a whole-number tax amount")
    void zeroDecimalCurrency() {
        Tax tax = ReceiptTaxParser.parse(p("VAT", "10%", null), d("110000"), "VND");

        assertEquals(d("10000"), tax.amount());
    }

    @Test
    @DisplayName("a receipt with no tax shows NO TAX at 0.00 and the total as the subtotal")
    void noTax() {
        Tax tax = ReceiptTaxParser.parse(p(null, null, null), TOTAL, MYR);

        assertEquals(NO_TAX, tax.type());
        assertEquals(d("0.00"), tax.rate());
        assertEquals(d("0.00"), tax.amount());
        assertEquals(TOTAL, tax.subTotal());
    }

    @Test
    @DisplayName("a tax name with no figures is treated as no tax")
    void typeWithoutFigures() {
        assertEquals(
                NO_TAX, ReceiptTaxParser.parse(p(SST, null, null), TOTAL, MYR).type());
    }

    @Test
    @DisplayName("an unknown total cannot support a tax figure or a subtotal")
    void noTotal() {
        Tax tax = ReceiptTaxParser.parse(p(SST, SIX_PERCENT, null), null, MYR);

        assertEquals(NO_TAX, tax.type());
        assertNull(tax.subTotal());
    }

    @Test
    @DisplayName("an amount that is not smaller than the total is ignored")
    void amountLargerThanTotal() {
        Tax tax = ReceiptTaxParser.parse(p(SST, SIX_PERCENT, "200.00"), TOTAL, MYR);

        assertEquals(d(SIX), tax.amount());
    }

    @Test
    @DisplayName("a rate above 30 percent is treated as a misread")
    void rateTooHigh() {
        assertEquals(
                NO_TAX, ReceiptTaxParser.parse(p(SST, "60%", null), TOTAL, MYR).type());
    }

    @Test
    @DisplayName("a comma used as the decimal point in a rate is read")
    void commaRate() {
        assertEquals(
                d("7.50"),
                ReceiptTaxParser.parse(p("VAT", "7,5%", null), TOTAL, "EUR").rate());
    }

    @Test
    @DisplayName("the printed subtotal is used even when a service charge makes it add up short")
    void printedSubTotal() {
        Tax tax = ReceiptTaxParser.parse(new Printed(SST, "8%", EIGHT_EIGHTY, HUNDRED, null), d("118.80"), MYR);

        assertEquals(d(HUNDRED), tax.subTotal());
        assertEquals(d(EIGHT_EIGHTY), tax.amount());
    }

    @Test
    @DisplayName("a printed service charge is kept and the subtotal fits around it")
    void serviceCharge() {
        Tax tax = ReceiptTaxParser.parse(new Printed(SST, "8%", EIGHT_EIGHTY, HUNDRED, TEN), d("118.80"), MYR);

        assertEquals(d(TEN), tax.serviceCharge());
        assertEquals(d(HUNDRED), tax.subTotal());
    }

    @Test
    @DisplayName("without a printed subtotal it is the total minus tax and service charge")
    void subTotalAfterServiceCharge() {
        Tax tax = ReceiptTaxParser.parse(new Printed(SST, "8%", EIGHT_EIGHTY, null, TEN), d("118.80"), MYR);

        assertEquals(d(HUNDRED), tax.subTotal());
    }

    @Test
    @DisplayName("a service charge on a receipt with no tax is still captured")
    void serviceChargeWithoutTax() {
        Tax tax = ReceiptTaxParser.parse(new Printed(null, null, null, null, "5.00"), d("55.00"), MYR);

        assertEquals(NO_TAX, tax.type());
        assertEquals(d("5.00"), tax.serviceCharge());
        assertEquals(d("50.00"), tax.subTotal());
    }

    @Test
    @DisplayName("no service charge on the receipt leaves it null")
    void noServiceCharge() {
        assertNull(ReceiptTaxParser.parse(p(GST, "9%", NINE), SGD_TOTAL, SGD).serviceCharge());
    }

    @Test
    @DisplayName("without a printed subtotal it is the total minus tax")
    void subTotalFromTotal() {
        Tax tax = ReceiptTaxParser.parse(p(GST, "9%", NINE), SGD_TOTAL, SGD);

        assertEquals(d(HUNDRED), tax.subTotal());
    }

    @Test
    @DisplayName("a printed subtotal that cannot fit with the tax inside the total is ignored")
    void subTotalTooLarge() {
        Tax tax = ReceiptTaxParser.parse(new Printed(GST, "9%", NINE, "109.00", null), SGD_TOTAL, SGD);

        assertEquals(d(HUNDRED), tax.subTotal());
    }
}
