package com.aisolutions.hrmanagement.util;

public final class StringNormalizer {

    private StringNormalizer() {}

    public static String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    public static String collapseWhitespace(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    public static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
