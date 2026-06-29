package com.aisolutions.hrmanagement.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bigram-based fuzzy matching — mirrors the frontend OcrTrainingService
 * similarity algorithm so behaviour is consistent.
 *
 * Score 0.0 = no similarity, 1.0 = identical.
 * Threshold ~0.7 is a reasonable "same merchant with OCR noise" cutoff.
 */
public final class FuzzyMatcher {

    private FuzzyMatcher() {}

    public static double similarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        if (a.equals(b)) return 1.0;
        if (a.length() < 2 || b.length() < 2) return 0.0;

        List<String> bigramsA = bigrams(a);
        List<String> bigramsB = bigrams(b);

        Map<String, Integer> countsB = new HashMap<>();
        for (String bg : bigramsB) countsB.merge(bg, 1, Integer::sum);

        int intersection = 0;
        for (String bg : bigramsA) {
            Integer c = countsB.get(bg);
            if (c != null && c > 0) {
                intersection++;
                countsB.put(bg, c - 1);
            }
        }
        return (2.0 * intersection) / (bigramsA.size() + bigramsB.size());
    }

    private static List<String> bigrams(String s) {
        List<String> list = new ArrayList<>(Math.max(0, s.length() - 1));
        for (int i = 0; i < s.length() - 1; i++) {
            list.add(s.substring(i, i + 2));
        }
        return list;
    }
}
