package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.OcrCorrection;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
@WithSession
public class OcrCorrectionRepository implements PanacheRepositoryBase<OcrCorrection, Long> {

    public Uni<OcrCorrection> save(OcrCorrection entity) {
        return getSession().flatMap(session ->
            session.persist(entity).replaceWith(entity));
    }

    public Uni<List<OcrCorrection>> findAllOrderedByTimestampDesc() {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM OcrCorrection ORDER BY timestamp DESC",
                OcrCorrection.class)
                .getResultList()
        );
    }

    /**
     * Aggregate counts used by the stats dashboard.
     * Returns: [total, merchantErr, numberErr, dateErr, amountErr]
     */
    public Uni<long[]> getCorrectionCounts() {
        return getSession().flatMap(session ->
            session.createQuery(
                "SELECT COUNT(c), " +
                "SUM(CASE WHEN c.merchantCorrected = true THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN c.receiptNumberCorrected = true THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN c.dateCorrected = true THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN c.amountCorrected = true THEN 1 ELSE 0 END) " +
                "FROM OcrCorrection c", Object[].class)
                .getSingleResult()
                .map(row -> new long[]{
                    toLong(row[0]), toLong(row[1]), toLong(row[2]),
                    toLong(row[3]), toLong(row[4])
                })
        );
    }

    /**
     * Counts distinct merchants whose raw OCR text contains the given keyword.
     * Used to compute ConfirmedByCount for global field rules.
     */
    public Uni<Long> countDistinctMerchantsForKeyword(String fieldName, String keyword) {
        String fieldCondition = switch (fieldName) {
            case "ReceiptNumber" -> "c.correctedReceiptNumber IS NOT NULL";
            case "ReceiptDate"   -> "c.correctedReceiptDate IS NOT NULL";
            case "ReceiptAmount" -> "c.correctedReceiptAmount IS NOT NULL";
            default              -> "c.correctedMerchantName IS NOT NULL";
        };

        return getSession().flatMap(session ->
            session.createQuery(
                "SELECT COUNT(DISTINCT c.correctedMerchantName) FROM OcrCorrection c " +
                "WHERE " + fieldCondition + " " +
                "AND c.correctedMerchantName IS NOT NULL " +
                "AND LOWER(c.rawText) LIKE :kw",
                Long.class)
                .setParameter("kw", "%" + keyword.toLowerCase() + "%")
                .getSingleResult()
        );
    }

    public Uni<List<OcrCorrection>> findRecentForFuzzyMatch(int limit) {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM OcrCorrection ORDER BY timestamp DESC",
                OcrCorrection.class)
                .setMaxResults(limit)
                .getResultList()
        );
    }

    public Uni<Boolean> deleteByIdSafely(Long id) {
        return getSession().flatMap(session ->
            session.find(OcrCorrection.class, id)
                .onItem().ifNotNull().transformToUni(e -> session.remove(e).replaceWith(true))
                .onItem().ifNull().continueWith(false)
        );
    }

    public Uni<Long> deleteAll() {
        return getSession().flatMap(session ->
            session.createQuery("DELETE FROM OcrCorrection").executeUpdate()
        ).map(Integer::longValue);
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return 0L;
    }
}
