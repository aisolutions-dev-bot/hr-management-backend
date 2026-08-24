package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.OcrMerchantRule;

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
public class OcrMerchantRuleRepository {

    public Uni<OcrMerchantRule> findByMerchantExact(SqlClient client, String merchantName) {
        return client.preparedQuery(
                "SELECT UniqId, MerchantName, ReceiptNumberKeyword, ReceiptNumberPattern, " +
                "DateKeyword, DateFormat, AmountKeyword, Confidence, HitCount, LastUsed, " +
                "EntryStaff, EntryDate FROM m20OcrMerchantRule WHERE MerchantName = ?")
            .execute(Tuple.tuple().addValue(merchantName))
            .map(rows -> rows.iterator().hasNext() ? toEntity(rows.iterator().next()) : null);
    }

    public Uni<List<OcrMerchantRule>> findAllOrdered(SqlClient client) {
        return client.preparedQuery(
                "SELECT UniqId, MerchantName, ReceiptNumberKeyword, ReceiptNumberPattern, " +
                "DateKeyword, DateFormat, AmountKeyword, Confidence, HitCount, LastUsed, " +
                "EntryStaff, EntryDate FROM m20OcrMerchantRule ORDER BY Confidence DESC, HitCount DESC")
            .execute()
            .map(this::toList);
    }

    public Uni<OcrMerchantRule> save(SqlClient client, OcrMerchantRule entity) {
        return client.preparedQuery(
                "INSERT INTO m20OcrMerchantRule (MerchantName, ReceiptNumberKeyword, ReceiptNumberPattern, " +
                "DateKeyword, DateFormat, AmountKeyword, Confidence, HitCount, LastUsed, " +
                "EntryStaff, EntryDate) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .execute(Tuple.tuple()
                .addValue(entity.getMerchantName())
                .addValue(entity.getReceiptNumberKeyword())
                .addValue(entity.getReceiptNumberPattern())
                .addValue(entity.getDateKeyword())
                .addValue(entity.getDateFormat())
                .addValue(entity.getAmountKeyword())
                .addValue(entity.getConfidence())
                .addValue(entity.getHitCount())
                .addValue(entity.getLastUsed())
                .addValue(entity.getEntryStaff())
                .addValue(entity.getEntryDate()))
            .flatMap(result -> {
                Long id = result.property(MySQLClient.LAST_INSERTED_ID);
                entity.setUniqId(id);
                return Uni.createFrom().item(entity);
            });
    }

    public Uni<OcrMerchantRule> update(SqlClient client, OcrMerchantRule entity) {
        return client.preparedQuery(
                "UPDATE m20OcrMerchantRule SET MerchantName = ?, ReceiptNumberKeyword = ?, " +
                "ReceiptNumberPattern = ?, DateKeyword = ?, DateFormat = ?, AmountKeyword = ?, " +
                "Confidence = ?, HitCount = ?, LastUsed = ?, EntryStaff = ?, EntryDate = ? " +
                "WHERE UniqId = ?")
            .execute(Tuple.tuple()
                .addValue(entity.getMerchantName())
                .addValue(entity.getReceiptNumberKeyword())
                .addValue(entity.getReceiptNumberPattern())
                .addValue(entity.getDateKeyword())
                .addValue(entity.getDateFormat())
                .addValue(entity.getAmountKeyword())
                .addValue(entity.getConfidence())
                .addValue(entity.getHitCount())
                .addValue(entity.getLastUsed())
                .addValue(entity.getEntryStaff())
                .addValue(entity.getEntryDate())
                .addValue(entity.getUniqId()))
            .map(v -> entity);
    }

    public Uni<Boolean> deleteByIdSafely(SqlClient client, Long id) {
        return client.preparedQuery("DELETE FROM m20OcrMerchantRule WHERE UniqId = ?")
            .execute(Tuple.tuple().addValue(id))
            .map(result -> result.rowCount() > 0);
    }

    public Uni<Long> deleteAll(SqlClient client) {
        return client.preparedQuery("DELETE FROM m20OcrMerchantRule")
            .execute()
            .map(result -> (long) result.rowCount());
    }

    public Uni<Long> countAll(SqlClient client) {
        return client.preparedQuery("SELECT COUNT(*) AS cnt FROM m20OcrMerchantRule")
            .execute()
            .map(rows -> rows.iterator().next().getLong("cnt"));
    }

    private List<OcrMerchantRule> toList(RowSet<Row> rows) {
        List<OcrMerchantRule> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    private OcrMerchantRule toEntity(Row row) {
        OcrMerchantRule e = new OcrMerchantRule();
        e.setUniqId(row.getLong("UniqId"));
        e.setMerchantName(row.getString("MerchantName"));
        e.setReceiptNumberKeyword(row.getString("ReceiptNumberKeyword"));
        e.setReceiptNumberPattern(row.getString("ReceiptNumberPattern"));
        e.setDateKeyword(row.getString("DateKeyword"));
        e.setDateFormat(row.getString("DateFormat"));
        e.setAmountKeyword(row.getString("AmountKeyword"));
        e.setConfidence(row.getInteger("Confidence"));
        e.setHitCount(row.getInteger("HitCount"));
        e.setLastUsed(row.getLocalDateTime("LastUsed"));
        e.setEntryStaff(row.getString("EntryStaff"));
        e.setEntryDate(row.getLocalDateTime("EntryDate"));
        return e;
    }
}
