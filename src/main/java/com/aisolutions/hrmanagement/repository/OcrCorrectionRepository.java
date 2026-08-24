package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.OcrCorrection;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.mysqlclient.MySQLClient;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Slf4j
public class OcrCorrectionRepository {

    public Uni<OcrCorrection> save(SqlClient client, OcrCorrection entity) {
        return client.preparedQuery(
                "INSERT INTO m20OcrCorrections (StaffId, Timestamp, RawText, OcrMerchantName, " +
                "OcrReceiptNumber, OcrReceiptDate, OcrReceiptAmount, CorrectedMerchantName, " +
                "CorrectedReceiptNumber, CorrectedReceiptDate, CorrectedReceiptAmount, " +
                "MerchantCorrected, ReceiptNumberCorrected, DateCorrected, AmountCorrected) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .execute(Tuple.tuple()
                .addValue(entity.getStaffId())
                .addValue(entity.getTimestamp())
                .addValue(entity.getRawText())
                .addValue(entity.getOcrMerchantName())
                .addValue(entity.getOcrReceiptNumber())
                .addValue(entity.getOcrReceiptDate())
                .addValue(entity.getOcrReceiptAmount())
                .addValue(entity.getCorrectedMerchantName())
                .addValue(entity.getCorrectedReceiptNumber())
                .addValue(entity.getCorrectedReceiptDate())
                .addValue(entity.getCorrectedReceiptAmount())
                .addValue(entity.getMerchantCorrected())
                .addValue(entity.getReceiptNumberCorrected())
                .addValue(entity.getDateCorrected())
                .addValue(entity.getAmountCorrected()))
            .flatMap(result -> {
                Long id = result.property(MySQLClient.LAST_INSERTED_ID);
                entity.setUniqId(id);
                return Uni.createFrom().item(entity);
            });
    }

    public Uni<List<OcrCorrection>> findAllOrderedByTimestampDesc(SqlClient client) {
        return client.preparedQuery(
                "SELECT UniqId, StaffId, Timestamp, RawText, OcrMerchantName, OcrReceiptNumber, " +
                "OcrReceiptDate, OcrReceiptAmount, CorrectedMerchantName, CorrectedReceiptNumber, " +
                "CorrectedReceiptDate, CorrectedReceiptAmount, MerchantCorrected, " +
                "ReceiptNumberCorrected, DateCorrected, AmountCorrected " +
                "FROM m20OcrCorrections ORDER BY Timestamp DESC")
            .execute()
            .map(this::toList);
    }

    /**
     * Aggregate counts used by the stats dashboard.
     * Returns: [total, merchantErr, numberErr, dateErr, amountErr]
     */
    public Uni<long[]> getCorrectionCounts(SqlClient client) {
        return client.preparedQuery(
                "SELECT COUNT(*) AS total, " +
                "SUM(CASE WHEN MerchantCorrected = true THEN 1 ELSE 0 END) AS merchantErr, " +
                "SUM(CASE WHEN ReceiptNumberCorrected = true THEN 1 ELSE 0 END) AS numberErr, " +
                "SUM(CASE WHEN DateCorrected = true THEN 1 ELSE 0 END) AS dateErr, " +
                "SUM(CASE WHEN AmountCorrected = true THEN 1 ELSE 0 END) AS amountErr " +
                "FROM m20OcrCorrections")
            .execute()
            .map(rows -> {
                Row row = rows.iterator().next();
                return new long[]{
                    toLong(row.getValue("total")),
                    toLong(row.getValue("merchantErr")),
                    toLong(row.getValue("numberErr")),
                    toLong(row.getValue("dateErr")),
                    toLong(row.getValue("amountErr"))
                };
            });
    }

    /**
     * Counts distinct merchants whose raw OCR text contains the given keyword.
     */
    public Uni<Long> countDistinctMerchantsForKeyword(SqlClient client, String fieldName, String keyword) {
        String fieldCondition = switch (fieldName) {
            case "ReceiptNumber" -> "CorrectedReceiptNumber IS NOT NULL";
            case "ReceiptDate"   -> "CorrectedReceiptDate IS NOT NULL";
            case "ReceiptAmount" -> "CorrectedReceiptAmount IS NOT NULL";
            default              -> "CorrectedMerchantName IS NOT NULL";
        };

        return client.preparedQuery(
                "SELECT COUNT(DISTINCT CorrectedMerchantName) AS cnt FROM m20OcrCorrections " +
                "WHERE " + fieldCondition + " " +
                "AND CorrectedMerchantName IS NOT NULL " +
                "AND LOWER(RawText) LIKE ?")
            .execute(Tuple.tuple().addValue("%" + keyword.toLowerCase() + "%"))
            .map(rows -> rows.iterator().next().getLong("cnt"));
    }

    public Uni<List<OcrCorrection>> findRecentForFuzzyMatch(SqlClient client, int limit) {
        return client.preparedQuery(
                "SELECT UniqId, StaffId, Timestamp, RawText, OcrMerchantName, OcrReceiptNumber, " +
                "OcrReceiptDate, OcrReceiptAmount, CorrectedMerchantName, CorrectedReceiptNumber, " +
                "CorrectedReceiptDate, CorrectedReceiptAmount, MerchantCorrected, " +
                "ReceiptNumberCorrected, DateCorrected, AmountCorrected " +
                "FROM m20OcrCorrections ORDER BY Timestamp DESC LIMIT ?")
            .execute(Tuple.tuple().addValue(limit))
            .map(this::toList);
    }

    public Uni<Boolean> deleteByIdSafely(SqlClient client, Long id) {
        return client.preparedQuery("DELETE FROM m20OcrCorrections WHERE UniqId = ?")
            .execute(Tuple.tuple().addValue(id))
            .map(result -> result.rowCount() > 0);
    }

    public Uni<Long> deleteAll(SqlClient client) {
        return client.preparedQuery("DELETE FROM m20OcrCorrections")
            .execute()
            .map(result -> (long) result.rowCount());
    }

    private List<OcrCorrection> toList(RowSet<Row> rows) {
        List<OcrCorrection> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    private OcrCorrection toEntity(Row row) {
        OcrCorrection e = new OcrCorrection();
        e.setUniqId(row.getLong("UniqId"));
        e.setStaffId(row.getString("StaffId"));
        e.setTimestamp(row.getLocalDateTime("Timestamp"));
        e.setRawText(row.getString("RawText"));
        e.setOcrMerchantName(row.getString("OcrMerchantName"));
        e.setOcrReceiptNumber(row.getString("OcrReceiptNumber"));
        e.setOcrReceiptDate(row.getString("OcrReceiptDate"));
        e.setOcrReceiptAmount(row.getBigDecimal("OcrReceiptAmount"));
        e.setCorrectedMerchantName(row.getString("CorrectedMerchantName"));
        e.setCorrectedReceiptNumber(row.getString("CorrectedReceiptNumber"));
        e.setCorrectedReceiptDate(row.getString("CorrectedReceiptDate"));
        e.setCorrectedReceiptAmount(row.getBigDecimal("CorrectedReceiptAmount"));
        e.setMerchantCorrected(row.getBoolean("MerchantCorrected"));
        e.setReceiptNumberCorrected(row.getBoolean("ReceiptNumberCorrected"));
        e.setDateCorrected(row.getBoolean("DateCorrected"));
        e.setAmountCorrected(row.getBoolean("AmountCorrected"));
        return e;
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return 0L;
    }
}
