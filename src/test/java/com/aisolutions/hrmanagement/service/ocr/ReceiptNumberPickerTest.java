package com.aisolutions.hrmanagement.service.ocr;

import java.util.List;

import com.aisolutions.hrmanagement.service.ocr.ReceiptNumberPicker.LabelledNumber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Covers the Receipt No, Invoice No, Order No, Reference No order and the labels that are ignored. */
class ReceiptNumberPickerTest {

    private static final String RECEIPT = "Receipt No";
    private static final String INVOICE = "Invoice No";
    private static final String ORDER = "Order No";
    private static final String REFERENCE = "Ref No";
    private static final String TABLE = "Table No";
    private static final String INVOICE_VALUE = "MV01/76048";
    private static final String ORDER_VALUE = "ZE004167";
    private static final String REFERENCE_VALUE = "Z0-1";

    private static LabelledNumber n(String label, String value) {
        return new LabelledNumber(label, value);
    }

    @Test
    @DisplayName("Receipt No wins when it is printed")
    void receiptNoWins() {
        assertEquals(
                "R-1001",
                ReceiptNumberPicker.pick(List.of(
                        n(INVOICE, INVOICE_VALUE),
                        n(RECEIPT, "R-1001"),
                        n(ORDER, ORDER_VALUE),
                        n(REFERENCE, REFERENCE_VALUE))));
    }

    @Test
    @DisplayName("Falls back to Invoice, then Order, then Reference No")
    void fallbackOrder() {
        assertEquals(
                INVOICE_VALUE,
                ReceiptNumberPicker.pick(
                        List.of(n(REFERENCE, REFERENCE_VALUE), n(ORDER, ORDER_VALUE), n(INVOICE, INVOICE_VALUE))));
        assertEquals(
                ORDER_VALUE, ReceiptNumberPicker.pick(List.of(n(REFERENCE, REFERENCE_VALUE), n(ORDER, ORDER_VALUE))));
        assertEquals(REFERENCE_VALUE, ReceiptNumberPicker.pick(List.of(n(REFERENCE, REFERENCE_VALUE))));
    }

    @Test
    @DisplayName("A table number is never taken, even when it is the only number")
    void tableNumberIsIgnored() {
        assertEquals(
                INVOICE_VALUE,
                ReceiptNumberPicker.pick(List.of(n(TABLE, "21"), n(INVOICE.toUpperCase(), INVOICE_VALUE))));
        assertNull(ReceiptNumberPicker.pick(List.of(n(TABLE, "21"), n("Pump No", "10"), n("Queue No", "112"))));
    }

    @Test
    @DisplayName("Terminal, approval and transaction numbers are ignored")
    void paymentNumbersAreIgnored() {
        assertNull(ReceiptNumberPicker.pick(
                List.of(n("Terminal", "10001002"), n("B.Approval", "243004"), n("Site Tran No", "156132"))));
    }

    @Test
    @DisplayName("Label wording and punctuation variants are recognised")
    void labelVariants() {
        assertEquals("7f8f99", ReceiptNumberPicker.pick(List.of(n("INV NO.", "7f8f99"))));
        assertEquals("55", ReceiptNumberPicker.pick(List.of(n("Tax Invoice No:", "55"))));
        assertEquals("88", ReceiptNumberPicker.pick(List.of(n("Receipt #", "88"))));
        assertEquals("9", ReceiptNumberPicker.pick(List.of(n("CDB Reference No.", "9"))));
    }

    @Test
    @DisplayName("A code of letters and digits, or letters only, is accepted")
    void lettersAndDigitCodes() {
        assertEquals("ac9bca", ReceiptNumberPicker.pick(List.of(n("INV NO.", "ac9bca"))));
        assertEquals("abcdef", ReceiptNumberPicker.pick(List.of(n("INV NO.", "abcdef"))));
    }

    @Test
    @DisplayName("An invoice date is not an invoice number")
    void invoiceDateIsIgnored() {
        assertNull(ReceiptNumberPicker.pick(List.of(n("Invoice Date", "07/09/2026"))));
    }

    @Test
    @DisplayName("Returns null when there is nothing usable")
    void nothingUsable() {
        assertNull(ReceiptNumberPicker.pick(null));
        assertNull(ReceiptNumberPicker.pick(List.of()));
        assertNull(ReceiptNumberPicker.pick(List.of(n(RECEIPT, "N/A"), n(INVOICE, " "), n(ORDER, null), n(null, "5"))));
    }

    @Test
    @DisplayName("Trims, drops a leading # or colon and collapses spaces")
    void cleansTheValue() {
        assertEquals("0012 3456", ReceiptNumberPicker.pick(List.of(n(RECEIPT, "  # 0012   3456 "))));
        assertEquals("AB123", ReceiptNumberPicker.pick(List.of(n(RECEIPT, ": AB123"))));
    }
}
