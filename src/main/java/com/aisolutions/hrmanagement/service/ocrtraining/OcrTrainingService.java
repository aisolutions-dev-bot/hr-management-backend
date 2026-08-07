package com.aisolutions.hrmanagement.service.ocrtraining;

import com.aisolutions.hrmanagement.dto.*;
import com.aisolutions.hrmanagement.entity.*;
import com.aisolutions.hrmanagement.enums.OcrFieldName;
import com.aisolutions.hrmanagement.repository.*;
import com.aisolutions.hrmanagement.service.CurrentUserService;
import com.aisolutions.hrmanagement.util.FuzzyMatcher;
import com.aisolutions.hrmanagement.util.StringNormalizer;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import com.aisolutions.shared.util.DateUtil;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two-layer OCR training engine.
 *
 * ── LAYER 1: Global Field Rules ─────────────────────────────────────────
 *   Keywords that work across ALL merchants (e.g. "INV#" → Receipt Number).
 *   When several distinct merchants confirm the same keyword, its confidence
 *   rises via ConfirmedByCount.
 *
 * ── LAYER 2: Merchant-Specific Rules ────────────────────────────────────
 *   Per-merchant overrides for merchants that use non-standard labels
 *   (e.g. McDonald's uses "Order #" instead of "INV#").
 *
 * ── LEARNING FLOW (recordCorrection) ────────────────────────────────────
 *   1. Persist raw correction record in m20OcrCorrections.
 *   2. If merchant was present → upsert m20OcrMerchantAlias.
 *   3. For each corrected field, locate the label/keyword preceding the
 *      value in the raw OCR text and upsert BOTH a merchant-specific rule
 *      and a global rule. Global rules recompute ConfirmedByCount via a
 *      distinct-merchant count.
 *
 * ── APPLICATION FLOW (applyTraining) ────────────────────────────────────
 *   Order of precedence (first match wins per field):
 *      a) Merchant alias lookup — resolves merchant name first
 *      b) Merchant-specific rule for that merchant
 *      c) Global field rules (highest-confidence keyword wins)
 *      d) Fuzzy-match fallback against past corrections
 */
@ApplicationScoped
public class OcrTrainingService {

    // Tunables
    private static final int MIN_RULE_CONFIDENCE_FOR_APPLY = 20;
    private static final double FUZZY_MERCHANT_THRESHOLD = 0.70;
    private static final double FUZZY_CORRECTION_THRESHOLD = 0.60;
    private static final int CORRECTION_TEXT_COMPARE_PREFIX = 200;
    private static final int CORRECTION_FUZZY_POOL_SIZE = 50;

    @Inject OcrCorrectionRepository correctionRepo;
    @Inject OcrMerchantAliasRepository aliasRepo;
    @Inject OcrMerchantRuleRepository merchantRuleRepo;
    @Inject OcrGlobalFieldRuleRepository globalRuleRepo;
    @Inject CurrentUserService currentUserService;

    // ─────────────────────────────────────────────────────────
    //  RECORD CORRECTION
    // ─────────────────────────────────────────────────────────

    public Uni<Void> recordCorrection(RecordCorrectionRequestDTO request) {
        if (request == null || request.getOcrResult() == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("ocrResult is required"));
        }

        return currentUserService.getCurrentUser()
            .onItem().transformToUni(user -> {
                String staffId = (user != null) ? user.getStaffId() : null;
                return Panache.withTransaction(() -> doRecordCorrection(request, staffId));
            });
    }

    private Uni<Void> doRecordCorrection(RecordCorrectionRequestDTO req, String staffId) {
        OcrReceiptResultDTO ocr = req.getOcrResult();
        String rawText = ocr.getRawText() != null ? ocr.getRawText() : "";

        OcrCorrection rec = new OcrCorrection();
        rec.setStaffId(staffId);
        rec.setTimestamp(DateUtil.nowSGT());
        rec.setRawText(rawText);

        rec.setOcrMerchantName(ocr.getMerchantName());
        rec.setOcrReceiptNumber(ocr.getReceiptNumber());
        rec.setOcrReceiptDate(ocr.getReceiptDate());
        rec.setOcrReceiptAmount(ocr.getReceiptAmount());

        rec.setCorrectedMerchantName(req.getCorrectedMerchantName());
        rec.setCorrectedReceiptNumber(req.getCorrectedReceiptNumber());
        rec.setCorrectedReceiptDate(req.getCorrectedReceiptDate());
        rec.setCorrectedReceiptAmount(req.getCorrectedReceiptAmount());

        rec.setMerchantCorrected(fieldChanged(ocr.getMerchantName(), req.getCorrectedMerchantName()));
        rec.setReceiptNumberCorrected(fieldChanged(ocr.getReceiptNumber(), req.getCorrectedReceiptNumber()));
        rec.setDateCorrected(fieldChanged(ocr.getReceiptDate(), req.getCorrectedReceiptDate()));
        rec.setAmountCorrected(amountChanged(ocr.getReceiptAmount(), req.getCorrectedReceiptAmount()));

        final List<String> lines = splitLines(rawText);
        final String merchantKey = StringNormalizer.normalise(
                req.getCorrectedMerchantName() != null
                        ? req.getCorrectedMerchantName()
                        : (ocr.getMerchantName() != null ? ocr.getMerchantName() : ""));

        return correctionRepo.save(rec)
            .flatMap(ignore -> learnMerchantAlias(
                    ocr.getMerchantName(), req.getCorrectedMerchantName(),
                    rec.getMerchantCorrected(), staffId))
            .flatMap(ignore -> learnFieldKeywords(
                    OcrFieldName.RECEIPT_NUMBER, req.getCorrectedReceiptNumber(),
                    lines, merchantKey, staffId))
            .flatMap(ignore -> learnDateKeywords(
                    req.getCorrectedReceiptDate(), lines, merchantKey, staffId))
            .flatMap(ignore -> learnAmountKeywords(
                    req.getCorrectedReceiptAmount(), lines, merchantKey, staffId))
            .replaceWithVoid();
    }

    private Uni<Void> learnMerchantAlias(
            String ocrName, String correctName, boolean wasCorrected, String staffId) {

        if (StringNormalizer.isBlank(correctName)) return Uni.createFrom().voidItem();

        String pattern = StringNormalizer.normalise(
                !StringNormalizer.isBlank(ocrName) ? ocrName : correctName);
        if (pattern.isEmpty()) return Uni.createFrom().voidItem();

        return aliasRepo.findByPatternExact(pattern)
            .flatMap(existing -> {
                if (existing != null) {
                    existing.setCorrectName(correctName);
                    existing.setHitCount(existing.getHitCount() + 1);
                    existing.setConfidence(Math.min(100,
                            existing.getConfidence() + (wasCorrected ? 5 : 10)));
                    existing.setLastUsed(DateUtil.nowSGT());
                    return aliasRepo.update(existing).replaceWithVoid();
                }
                OcrMerchantAlias a = new OcrMerchantAlias();
                a.setOcrPattern(pattern);
                a.setCorrectName(correctName);
                a.setConfidence(wasCorrected ? 30 : 60);
                a.setHitCount(1);
                a.setLastUsed(DateUtil.nowSGT());
                a.setEntryStaff(staffId);
                a.setEntryDate(DateUtil.nowSGT());
                return aliasRepo.save(a).replaceWithVoid();
            });
    }

    private Uni<Void> learnFieldKeywords(
            OcrFieldName field, String correctedValue,
            List<String> lines, String merchantKey, String staffId) {

        if (StringNormalizer.isBlank(correctedValue)) return Uni.createFrom().voidItem();

        String keyword = findKeywordBefore(lines, correctedValue);
        if (keyword == null) return Uni.createFrom().voidItem();

        String pattern = buildNumberPattern(correctedValue);

        Uni<Void> merchantRuleLearn = merchantKey.isEmpty()
                ? Uni.createFrom().voidItem()
                : upsertMerchantRuleForField(merchantKey, field, keyword, pattern, staffId);

        Uni<Void> globalRuleLearn = upsertGlobalRule(field, keyword, pattern, null);

        return merchantRuleLearn.flatMap(v -> globalRuleLearn);
    }

    private Uni<Void> learnDateKeywords(
            String correctedIsoDate, List<String> lines,
            String merchantKey, String staffId) {

        if (StringNormalizer.isBlank(correctedIsoDate)) return Uni.createFrom().voidItem();

        String lineWithDate = findLineContainingDate(lines, correctedIsoDate);
        if (lineWithDate == null) return Uni.createFrom().voidItem();

        String dateKeyword = extractDateKeyword(lineWithDate);
        String dateFormat = detectDateFormat(lineWithDate);

        Uni<Void> merchantRuleLearn = merchantKey.isEmpty()
                ? Uni.createFrom().voidItem()
                : upsertMerchantRuleDate(merchantKey, dateKeyword, dateFormat, staffId);

        Uni<Void> globalRuleLearn = (dateKeyword != null)
                ? upsertGlobalRule(OcrFieldName.RECEIPT_DATE, dateKeyword, null, dateFormat)
                : Uni.createFrom().voidItem();

        return merchantRuleLearn.flatMap(v -> globalRuleLearn);
    }

    private Uni<Void> learnAmountKeywords(
            BigDecimal correctedAmount, List<String> lines,
            String merchantKey, String staffId) {

        if (correctedAmount == null) return Uni.createFrom().voidItem();

        String amtStr = correctedAmount.toPlainString();
        if (!amtStr.contains(".")) amtStr = amtStr + ".00";

        String keyword = findKeywordBeforeAmount(lines, amtStr);
        if (keyword == null) return Uni.createFrom().voidItem();

        Uni<Void> merchantRuleLearn = merchantKey.isEmpty()
                ? Uni.createFrom().voidItem()
                : upsertMerchantRuleAmount(merchantKey, keyword, staffId);

        Uni<Void> globalRuleLearn = upsertGlobalRule(
                OcrFieldName.RECEIPT_AMOUNT, keyword, null, null);

        return merchantRuleLearn.flatMap(v -> globalRuleLearn);
    }

    // ── Upsert helpers ────────────────────────────────────────

    private Uni<Void> upsertMerchantRuleForField(
            String merchantKey, OcrFieldName field,
            String keyword, String pattern, String staffId) {

        return merchantRuleRepo.findByMerchantExact(merchantKey)
            .flatMap(existing -> {
                OcrMerchantRule rule = existing != null ? existing : newMerchantRule(merchantKey, staffId);
                if (field == OcrFieldName.RECEIPT_NUMBER) {
                    rule.setReceiptNumberKeyword(keyword);
                    if (pattern != null) rule.setReceiptNumberPattern(pattern);
                }
                rule.setHitCount(rule.getHitCount() + 1);
                rule.setConfidence(Math.min(100, rule.getConfidence() + 8));
                rule.setLastUsed(DateUtil.nowSGT());
                return existing != null
                        ? merchantRuleRepo.update(rule).replaceWithVoid()
                        : merchantRuleRepo.save(rule).replaceWithVoid();
            });
    }

    private Uni<Void> upsertMerchantRuleDate(
            String merchantKey, String dateKeyword, String dateFormat, String staffId) {

        return merchantRuleRepo.findByMerchantExact(merchantKey)
            .flatMap(existing -> {
                OcrMerchantRule rule = existing != null ? existing : newMerchantRule(merchantKey, staffId);
                if (dateKeyword != null) rule.setDateKeyword(dateKeyword);
                if (dateFormat != null) rule.setDateFormat(dateFormat);
                rule.setHitCount(rule.getHitCount() + 1);
                rule.setConfidence(Math.min(100, rule.getConfidence() + 8));
                rule.setLastUsed(DateUtil.nowSGT());
                return existing != null
                        ? merchantRuleRepo.update(rule).replaceWithVoid()
                        : merchantRuleRepo.save(rule).replaceWithVoid();
            });
    }

    private Uni<Void> upsertMerchantRuleAmount(
            String merchantKey, String keyword, String staffId) {

        return merchantRuleRepo.findByMerchantExact(merchantKey)
            .flatMap(existing -> {
                OcrMerchantRule rule = existing != null ? existing : newMerchantRule(merchantKey, staffId);
                rule.setAmountKeyword(keyword);
                rule.setHitCount(rule.getHitCount() + 1);
                rule.setConfidence(Math.min(100, rule.getConfidence() + 8));
                rule.setLastUsed(DateUtil.nowSGT());
                return existing != null
                        ? merchantRuleRepo.update(rule).replaceWithVoid()
                        : merchantRuleRepo.save(rule).replaceWithVoid();
            });
    }

    private OcrMerchantRule newMerchantRule(String merchantKey, String staffId) {
        OcrMerchantRule r = new OcrMerchantRule();
        r.setMerchantName(merchantKey);
        r.setConfidence(30);
        r.setHitCount(0);
        r.setLastUsed(DateUtil.nowSGT());
        r.setEntryStaff(staffId);
        r.setEntryDate(DateUtil.nowSGT());
        return r;
    }

    /**
     * Upserts a global rule; recomputes ConfirmedByCount via a distinct-merchant query.
     */
    private Uni<Void> upsertGlobalRule(
            OcrFieldName field, String keyword, String valuePattern, String dateFormat) {

        if (keyword == null || keyword.isBlank()) return Uni.createFrom().voidItem();

        final String fieldName = field.getValue();

        return globalRuleRepo.findByFieldAndKeyword(fieldName, keyword)
            .flatMap(existing -> correctionRepo.countDistinctMerchantsForKeyword(fieldName, keyword)
                .flatMap(distinctMerchants -> {
                    OcrGlobalFieldRule rule = existing != null ? existing : new OcrGlobalFieldRule();
                    boolean isNew = existing == null;
                    if (isNew) {
                        rule.setFieldName(fieldName);
                        rule.setKeyword(keyword);
                        rule.setConfidence(30);
                        rule.setHitCount(0);
                        rule.setConfirmedByCount(1);
                        rule.setEntryDate(DateUtil.nowSGT());
                    }
                    if (valuePattern != null) rule.setValuePattern(valuePattern);
                    if (dateFormat != null) rule.setDateFormat(dateFormat);
                    rule.setHitCount(rule.getHitCount() + 1);

                    int oldConfirmed = rule.getConfirmedByCount() != null ? rule.getConfirmedByCount() : 1;
                    int newConfirmed = Math.max(oldConfirmed, distinctMerchants.intValue());
                    rule.setConfirmedByCount(newConfirmed);

                    // Confidence: +5 per hit, +10 bonus when a NEW merchant confirms
                    int boost = 5 + (newConfirmed > oldConfirmed ? 10 : 0);
                    rule.setConfidence(Math.min(100, rule.getConfidence() + boost));
                    rule.setLastUsed(DateUtil.nowSGT());

                    return isNew
                            ? globalRuleRepo.save(rule).replaceWithVoid()
                            : globalRuleRepo.update(rule).replaceWithVoid();
                }));
    }

    // ─────────────────────────────────────────────────────────
    //  APPLY TRAINING
    // ─────────────────────────────────────────────────────────

    public Uni<OcrReceiptResultDTO> applyTraining(OcrReceiptResultDTO input) {
        if (input == null) return Uni.createFrom().failure(
                new IllegalArgumentException("Input is required"));

        OcrReceiptResultDTO result = copy(input);
        List<String> lines = splitLines(result.getRawText());

        return resolveMerchantName(result.getMerchantName(), lines)
            .flatMap(resolvedName -> {
                if (resolvedName != null) result.setMerchantName(resolvedName);

                String merchantKey = StringNormalizer.normalise(
                        result.getMerchantName() != null ? result.getMerchantName() : "");

                return applyMerchantSpecificRules(result, lines, merchantKey)
                    .flatMap(afterMerchant -> applyGlobalRules(afterMerchant, lines))
                    .flatMap(this::applyFuzzyFallback);
            });
    }

    private Uni<String> resolveMerchantName(String ocrName, List<String> lines) {
        if (!StringNormalizer.isBlank(ocrName)) {
            String norm = StringNormalizer.normalise(ocrName);
            return aliasRepo.findByPatternExact(norm)
                .flatMap(alias -> {
                    if (alias != null && alias.getConfidence() >= MIN_RULE_CONFIDENCE_FOR_APPLY) {
                        alias.setHitCount(alias.getHitCount() + 1);
                        alias.setLastUsed(DateUtil.nowSGT());
                        return Panache.withTransaction(() -> aliasRepo.update(alias))
                            .map(ignore -> alias.getCorrectName());
                    }
                    return fuzzyResolveFromLines(lines).map(r -> r != null ? r : ocrName);
                });
        }
        return fuzzyResolveFromLines(lines);
    }

    private Uni<String> fuzzyResolveFromLines(List<String> lines) {
        return aliasRepo.findAllOrdered().map(allAliases -> {
            for (String line : lines.subList(0, Math.min(5, lines.size()))) {
                String normLine = StringNormalizer.normalise(line);
                for (OcrMerchantAlias alias : allAliases) {
                    if (alias.getConfidence() < MIN_RULE_CONFIDENCE_FOR_APPLY) continue;
                    if (FuzzyMatcher.similarity(normLine, alias.getOcrPattern()) >= FUZZY_MERCHANT_THRESHOLD) {
                        return alias.getCorrectName();
                    }
                }
            }
            return null;
        });
    }

    private Uni<OcrReceiptResultDTO> applyMerchantSpecificRules(
            OcrReceiptResultDTO result, List<String> lines, String merchantKey) {

        if (merchantKey.isEmpty()) return Uni.createFrom().item(result);

        return merchantRuleRepo.findByMerchantExact(merchantKey)
            .map(rule -> {
                if (rule == null || rule.getConfidence() < MIN_RULE_CONFIDENCE_FOR_APPLY) {
                    return result;
                }
                if (rule.getReceiptNumberKeyword() != null && result.getReceiptNumber() == null) {
                    String v = findValueAfterKeyword(lines, rule.getReceiptNumberKeyword(),
                            rule.getReceiptNumberPattern(), false);
                    if (v != null) result.setReceiptNumber(v);
                }
                if (rule.getDateKeyword() != null && result.getReceiptDate() == null) {
                    String d = findDateAfterKeyword(lines, rule.getDateKeyword());
                    if (d != null) result.setReceiptDate(d);
                }
                if (rule.getAmountKeyword() != null && result.getReceiptAmount() == null) {
                    BigDecimal amt = findAmountAfterKeyword(lines, rule.getAmountKeyword());
                    if (amt != null) result.setReceiptAmount(amt);
                }
                return result;
            });
    }

    private Uni<OcrReceiptResultDTO> applyGlobalRules(
            OcrReceiptResultDTO result, List<String> lines) {

        Uni<OcrReceiptResultDTO> step1 = result.getReceiptNumber() != null
            ? Uni.createFrom().item(result)
            : globalRuleRepo.findByFieldName(OcrFieldName.RECEIPT_NUMBER.getValue())
                .map(rules -> {
                    for (OcrGlobalFieldRule r : rules) {
                        if (r.getConfidence() < MIN_RULE_CONFIDENCE_FOR_APPLY) continue;
                        String v = findValueAfterKeyword(lines, r.getKeyword(),
                                r.getValuePattern(), false);
                        if (v != null) { result.setReceiptNumber(v); break; }
                    }
                    return result;
                });

        Uni<OcrReceiptResultDTO> step2 = step1.flatMap(r -> {
            if (r.getReceiptDate() != null) return Uni.createFrom().item(r);
            return globalRuleRepo.findByFieldName(OcrFieldName.RECEIPT_DATE.getValue())
                .map(rules -> {
                    for (OcrGlobalFieldRule rule : rules) {
                        if (rule.getConfidence() < MIN_RULE_CONFIDENCE_FOR_APPLY) continue;
                        String d = findDateAfterKeyword(lines, rule.getKeyword());
                        if (d != null) { r.setReceiptDate(d); break; }
                    }
                    return r;
                });
        });

        return step2.flatMap(r -> {
            if (r.getReceiptAmount() != null) return Uni.createFrom().item(r);
            return globalRuleRepo.findByFieldName(OcrFieldName.RECEIPT_AMOUNT.getValue())
                .map(rules -> {
                    for (OcrGlobalFieldRule rule : rules) {
                        if (rule.getConfidence() < MIN_RULE_CONFIDENCE_FOR_APPLY) continue;
                        BigDecimal amt = findAmountAfterKeyword(lines, rule.getKeyword());
                        if (amt != null) { r.setReceiptAmount(amt); break; }
                    }
                    return r;
                });
        });
    }

    private Uni<OcrReceiptResultDTO> applyFuzzyFallback(OcrReceiptResultDTO result) {
        if (result.getMerchantName() != null && result.getReceiptNumber() != null
                && result.getReceiptDate() != null && result.getReceiptAmount() != null) {
            return Uni.createFrom().item(result);
        }

        String rawKey = StringNormalizer.normalise(
                substring(result.getRawText(), CORRECTION_TEXT_COMPARE_PREFIX));
        if (rawKey.isEmpty()) return Uni.createFrom().item(result);

        return correctionRepo.findRecentForFuzzyMatch(CORRECTION_FUZZY_POOL_SIZE)
            .map(recent -> {
                OcrCorrection best = null;
                double bestScore = 0;
                for (OcrCorrection c : recent) {
                    String k = StringNormalizer.normalise(
                            substring(c.getRawText(), CORRECTION_TEXT_COMPARE_PREFIX));
                    double s = FuzzyMatcher.similarity(rawKey, k);
                    if (s > bestScore && s >= FUZZY_CORRECTION_THRESHOLD) {
                        bestScore = s;
                        best = c;
                    }
                }
                if (best != null) {
                    if (result.getMerchantName() == null && best.getCorrectedMerchantName() != null)
                        result.setMerchantName(best.getCorrectedMerchantName());
                    if (result.getReceiptNumber() == null && best.getCorrectedReceiptNumber() != null)
                        result.setReceiptNumber(best.getCorrectedReceiptNumber());
                    if (result.getReceiptDate() == null && best.getCorrectedReceiptDate() != null)
                        result.setReceiptDate(best.getCorrectedReceiptDate());
                    if (result.getReceiptAmount() == null && best.getCorrectedReceiptAmount() != null)
                        result.setReceiptAmount(best.getCorrectedReceiptAmount());
                }
                return result;
            });
    }

    // ─────────────────────────────────────────────────────────
    //  STATS / LIST / DELETE
    // ─────────────────────────────────────────────────────────

    public Uni<OcrTrainingStatsDTO> getStats() {
        return correctionRepo.getCorrectionCounts()
            .flatMap(counts -> aliasRepo.countAll()
                .flatMap(aliasCount -> merchantRuleRepo.countAll()
                    .flatMap(ruleCount -> globalRuleRepo.countAll()
                        .map(globalCount -> {
                            long total = counts[0];
                            long mErr = counts[1], nErr = counts[2], dErr = counts[3], aErr = counts[4];
                            long corrected = Math.max(mErr, Math.max(nErr, Math.max(dErr, aErr)));

                            OcrTrainingStatsDTO.FieldAccuracy acc = new OcrTrainingStatsDTO.FieldAccuracy(
                                total > 0 ? (int) Math.round((total - mErr) * 100.0 / total) : 0,
                                total > 0 ? (int) Math.round((total - nErr) * 100.0 / total) : 0,
                                total > 0 ? (int) Math.round((total - dErr) * 100.0 / total) : 0,
                                total > 0 ? (int) Math.round((total - aErr) * 100.0 / total) : 0
                            );
                            return new OcrTrainingStatsDTO(
                                total, corrected, aliasCount, ruleCount, globalCount, acc);
                        }))));
    }

    public Uni<List<OcrMerchantAliasDTO>> listAliases() {
        return aliasRepo.findAllOrdered().map(list -> list.stream().map(this::toAliasDto).toList());
    }

    public Uni<List<OcrMerchantRuleDTO>> listMerchantRules() {
        return merchantRuleRepo.findAllOrdered().map(list -> list.stream().map(this::toMerchantRuleDto).toList());
    }

    public Uni<List<OcrGlobalFieldRuleDTO>> listGlobalRules() {
        return globalRuleRepo.findAllOrdered().map(list -> list.stream().map(this::toGlobalRuleDto).toList());
    }

    public Uni<List<OcrCorrectionDTO>> listCorrections() {
        return correctionRepo.findAllOrderedByTimestampDesc()
                .map(list -> list.stream().map(this::toCorrectionDto).toList());
    }

    public Uni<Boolean> deleteAlias(Long id) {
        return Panache.withTransaction(() -> aliasRepo.deleteByIdSafely(id));
    }

    public Uni<Boolean> deleteMerchantRule(Long id) {
        return Panache.withTransaction(() -> merchantRuleRepo.deleteByIdSafely(id));
    }

    public Uni<Boolean> deleteGlobalRule(Long id) {
        return Panache.withTransaction(() -> globalRuleRepo.deleteByIdSafely(id));
    }

    public Uni<Boolean> deleteCorrection(Long id) {
        return Panache.withTransaction(() -> correctionRepo.deleteByIdSafely(id));
    }

    public Uni<Void> clearAllTrainingData() {
        return Panache.withTransaction(() ->
            correctionRepo.deleteAll()
                .flatMap(x -> aliasRepo.deleteAll())
                .flatMap(x -> merchantRuleRepo.deleteAll())
                .flatMap(x -> globalRuleRepo.deleteAll())
                .replaceWithVoid()
        );
    }

    // ─────────────────────────────────────────────────────────
    //  TEXT ANALYSIS HELPERS
    // ─────────────────────────────────────────────────────────

    private List<String> splitLines(String text) {
        if (text == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String l : text.split("\\r?\\n")) {
            String t = l.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private String findKeywordBefore(List<String> lines, String value) {
        if (value == null || value.isBlank()) return null;
        for (String line : lines) {
            int idx = line.indexOf(value);
            if (idx <= 0) continue;
            String prefix = line.substring(0, idx).trim().replaceAll("[\\s:.\\-#]+$", "");
            if (prefix.length() >= 2 && prefix.length() <= 40) return prefix;
        }
        return null;
    }

    private String findLineContainingDate(List<String> lines, String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return null;
        try {
            String[] parts = isoDate.split("-");
            if (parts.length != 3) return null;
            int y = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            int d = Integer.parseInt(parts[2]);
            String[] candidates = new String[]{
                d + "/" + m + "/" + y,
                String.format("%02d/%02d/%d", d, m, y),
                d + "-" + m + "-" + y,
                String.format("%02d-%02d-%d", d, m, y),
                y + "-" + String.format("%02d", m) + "-" + String.format("%02d", d),
                d + "." + m + "." + y,
            };
            for (String line : lines) {
                for (String c : candidates) {
                    if (line.contains(c)) return line;
                }
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private static final Pattern DATE_KEYWORD_PATTERN = Pattern.compile(
        "\\b(date|dated|dt|date/time|datetime|trans\\s*date|bill\\s*date|invoice\\s*date|receipt\\s*date|order\\s*date|payment\\s*date)\\b",
        Pattern.CASE_INSENSITIVE);

    private String extractDateKeyword(String line) {
        Matcher m = DATE_KEYWORD_PATTERN.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private String detectDateFormat(String line) {
        if (line.matches(".*\\d{1,2}/\\d{1,2}/\\d{4}.*")) return "dd/MM/yyyy";
        if (line.matches(".*\\d{1,2}-\\d{1,2}-\\d{4}.*")) return "dd-MM-yyyy";
        if (line.matches(".*\\d{4}-\\d{1,2}-\\d{1,2}.*")) return "yyyy-MM-dd";
        if (line.matches(".*\\d{1,2}\\s+[A-Za-z]{3,}\\s+\\d{2,4}.*")) return "dd MMM yyyy";
        return null;
    }

    private String findKeywordBeforeAmount(List<String> lines, String amountStr) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            int idx = line.indexOf(amountStr);
            if (idx > 0) {
                String prefix = line.substring(0, idx)
                        .replaceAll("[$\u00A5\u20AC\u00A3]|SGD|MYR|RM|USD", "")
                        .trim()
                        .replaceAll("[\\s:.\\-#]+$", "");
                if (prefix.length() >= 3 && prefix.length() <= 40) return prefix;
            }
        }
        return null;
    }

    private String findValueAfterKeyword(
            List<String> lines, String keyword, String valuePattern, boolean isDate) {

        if (keyword == null) return null;
        String lower = keyword.toLowerCase();
        Pattern valRegex = null;
        if (valuePattern != null) {
            try { valRegex = Pattern.compile(valuePattern, Pattern.CASE_INSENSITIVE); }
            catch (Exception ignored) {}
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int idx = line.toLowerCase().indexOf(lower);
            if (idx < 0) continue;

            String after = line.substring(idx + keyword.length()).replaceAll("^[\\s:.\\-#]+", "");
            if (!after.isBlank()) {
                if (valRegex != null) {
                    Matcher m = valRegex.matcher(after);
                    if (m.find()) return m.group();
                }
                Matcher tok = Pattern.compile("[A-Z0-9][A-Z0-9\\-/]{2,19}", Pattern.CASE_INSENSITIVE).matcher(after);
                if (tok.find()) return tok.group();
            }

            if (i + 1 < lines.size()) {
                String next = lines.get(i + 1).trim();
                if (valRegex != null) {
                    Matcher m = valRegex.matcher(next);
                    if (m.find()) return m.group();
                }
                if (next.length() >= 3 && next.length() <= 25) return next;
            }
        }
        return null;
    }

    private String findDateAfterKeyword(List<String> lines, String keyword) {
        if (keyword == null) return null;
        String lower = keyword.toLowerCase();
        for (String line : lines) {
            if (!line.toLowerCase().contains(lower)) continue;
            Matcher m1 = Pattern.compile("(\\d{1,2})[/\\-\\.](\\d{1,2})[/\\-\\.](\\d{2,4})").matcher(line);
            if (m1.find()) {
                int d = Integer.parseInt(m1.group(1));
                int mo = Integer.parseInt(m1.group(2));
                int y = Integer.parseInt(m1.group(3));
                if (y < 100) y += 2000;
                if (isValidDate(y, mo, d)) {
                    return String.format("%04d-%02d-%02d", y, mo, d);
                }
            }
            Matcher m2 = Pattern.compile("(\\d{4})[/\\-\\.](\\d{1,2})[/\\-\\.](\\d{1,2})").matcher(line);
            if (m2.find()) {
                int y = Integer.parseInt(m2.group(1));
                int mo = Integer.parseInt(m2.group(2));
                int d = Integer.parseInt(m2.group(3));
                if (isValidDate(y, mo, d)) {
                    return String.format("%04d-%02d-%02d", y, mo, d);
                }
            }
        }
        return null;
    }

    private BigDecimal findAmountAfterKeyword(List<String> lines, String keyword) {
        if (keyword == null) return null;
        String lower = keyword.toLowerCase();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.toLowerCase().contains(lower)) continue;
            Matcher m = Pattern.compile("(\\d{1,3}(?:[,\\s]\\d{3})*\\.\\d{2}|\\d+\\.\\d{2})").matcher(line);
            if (m.find()) {
                try { return new BigDecimal(m.group(1).replaceAll("[,\\s]", "")); }
                catch (NumberFormatException ignored) {}
            }
            if (i + 1 < lines.size()) {
                Matcher m2 = Pattern.compile("(\\d{1,3}(?:[,\\s]\\d{3})*\\.\\d{2}|\\d+\\.\\d{2})").matcher(lines.get(i + 1));
                if (m2.find()) {
                    try { return new BigDecimal(m2.group(1).replaceAll("[,\\s]", "")); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        return null;
    }

    private boolean isValidDate(int y, int m, int d) {
        if (y < 2000 || y > DateUtil.nowSGT().getYear() + 1) return false;
        if (m < 1 || m > 12 || d < 1 || d > 31) return false;
        try {
            LocalDateTime.of(y, m, d, 0, 0);
            return true;
        } catch (Exception ex) { return false; }
    }

    private String buildNumberPattern(String sample) {
        if (sample == null) return null;
        return sample
            .replaceAll("[A-Za-z]+", "[A-Za-z]+")
            .replaceAll("\\d+", "\\\\d+")
            .replace("-", "\\-")
            .replace("/", "\\/");
    }

    // ─────────────────────────────────────────────────────────
    //  MAPPERS & UTILS
    // ─────────────────────────────────────────────────────────

    private boolean fieldChanged(String ocr, String corrected) {
        boolean oBlank = StringNormalizer.isBlank(ocr);
        boolean cBlank = StringNormalizer.isBlank(corrected);
        if (oBlank && cBlank) return false;
        if (oBlank ^ cBlank) return true;
        return !StringNormalizer.normalise(ocr).equals(StringNormalizer.normalise(corrected));
    }

    private boolean amountChanged(BigDecimal ocr, BigDecimal corrected) {
        if (ocr == null && corrected == null) return false;
        if (ocr == null || corrected == null) return true;
        return ocr.compareTo(corrected) != 0;
    }

    private OcrReceiptResultDTO copy(OcrReceiptResultDTO src) {
        OcrReceiptResultDTO dto = new OcrReceiptResultDTO();
        dto.setMerchantName(src.getMerchantName());
        dto.setReceiptNumber(src.getReceiptNumber());
        dto.setReceiptDate(src.getReceiptDate());
        dto.setReceiptAmount(src.getReceiptAmount());
        dto.setRawText(src.getRawText());
        dto.setSuccess(src.isSuccess());
        dto.setErrorMessage(src.getErrorMessage());
        return dto;
    }

    private String substring(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    private OcrMerchantAliasDTO toAliasDto(OcrMerchantAlias e) {
        return new OcrMerchantAliasDTO(e.getUniqId(), e.getOcrPattern(), e.getCorrectName(),
            e.getConfidence(), e.getHitCount(), e.getLastUsed(), e.getEntryStaff(), e.getEntryDate());
    }

    private OcrMerchantRuleDTO toMerchantRuleDto(OcrMerchantRule e) {
        return new OcrMerchantRuleDTO(e.getUniqId(), e.getMerchantName(),
            e.getReceiptNumberKeyword(), e.getReceiptNumberPattern(),
            e.getDateKeyword(), e.getDateFormat(), e.getAmountKeyword(),
            e.getConfidence(), e.getHitCount(), e.getLastUsed(),
            e.getEntryStaff(), e.getEntryDate());
    }

    private OcrGlobalFieldRuleDTO toGlobalRuleDto(OcrGlobalFieldRule e) {
        return new OcrGlobalFieldRuleDTO(e.getUniqId(), e.getFieldName(), e.getKeyword(),
            e.getValuePattern(), e.getDateFormat(),
            e.getConfidence(), e.getHitCount(), e.getConfirmedByCount(),
            e.getLastUsed(), e.getEntryDate());
    }

    private OcrCorrectionDTO toCorrectionDto(OcrCorrection e) {
        return new OcrCorrectionDTO(e.getUniqId(), e.getStaffId(), e.getTimestamp(), e.getRawText(),
            e.getOcrMerchantName(), e.getOcrReceiptNumber(), e.getOcrReceiptDate(), e.getOcrReceiptAmount(),
            e.getCorrectedMerchantName(), e.getCorrectedReceiptNumber(), e.getCorrectedReceiptDate(), e.getCorrectedReceiptAmount(),
            e.getMerchantCorrected(), e.getReceiptNumberCorrected(), e.getDateCorrected(), e.getAmountCorrected());
    }
}
