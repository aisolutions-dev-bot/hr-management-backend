package com.aisolutions.hrmanagement.enums;

public enum OcrFieldName {
    MERCHANT_NAME("MerchantName"),
    RECEIPT_NUMBER("ReceiptNumber"),
    RECEIPT_DATE("ReceiptDate"),
    RECEIPT_AMOUNT("ReceiptAmount");

    private final String value;

    OcrFieldName(String value) { this.value = value; }

    public String getValue() { return value; }

    public static OcrFieldName fromValue(String v) {
        if (v == null) return null;
        for (OcrFieldName f : values()) {
            if (f.value.equalsIgnoreCase(v)) return f;
        }
        return null;
    }
}
