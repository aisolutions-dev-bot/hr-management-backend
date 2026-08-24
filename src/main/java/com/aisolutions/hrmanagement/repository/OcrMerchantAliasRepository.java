package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.OcrMerchantAlias;

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
public class OcrMerchantAliasRepository {

    public Uni<OcrMerchantAlias> findByPatternExact(SqlClient client, String ocrPattern) {
        return client.preparedQuery(
                "SELECT UniqId, OcrPattern, CorrectName, Confidence, HitCount, LastUsed, " +
                "EntryStaff, EntryDate FROM m20OcrMerchantAlias WHERE OcrPattern = ?")
            .execute(Tuple.tuple().addValue(ocrPattern))
            .map(rows -> rows.iterator().hasNext() ? toEntity(rows.iterator().next()) : null);
    }

    public Uni<List<OcrMerchantAlias>> findAllOrdered(SqlClient client) {
        return client.preparedQuery(
                "SELECT UniqId, OcrPattern, CorrectName, Confidence, HitCount, LastUsed, " +
                "EntryStaff, EntryDate FROM m20OcrMerchantAlias ORDER BY Confidence DESC, HitCount DESC")
            .execute()
            .map(this::toList);
    }

    public Uni<OcrMerchantAlias> save(SqlClient client, OcrMerchantAlias entity) {
        return client.preparedQuery(
                "INSERT INTO m20OcrMerchantAlias (OcrPattern, CorrectName, Confidence, HitCount, " +
                "LastUsed, EntryStaff, EntryDate) VALUES (?, ?, ?, ?, ?, ?, ?)")
            .execute(Tuple.tuple()
                .addValue(entity.getOcrPattern())
                .addValue(entity.getCorrectName())
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

    public Uni<OcrMerchantAlias> update(SqlClient client, OcrMerchantAlias entity) {
        return client.preparedQuery(
                "UPDATE m20OcrMerchantAlias SET OcrPattern = ?, CorrectName = ?, Confidence = ?, " +
                "HitCount = ?, LastUsed = ?, EntryStaff = ?, EntryDate = ? WHERE UniqId = ?")
            .execute(Tuple.tuple()
                .addValue(entity.getOcrPattern())
                .addValue(entity.getCorrectName())
                .addValue(entity.getConfidence())
                .addValue(entity.getHitCount())
                .addValue(entity.getLastUsed())
                .addValue(entity.getEntryStaff())
                .addValue(entity.getEntryDate())
                .addValue(entity.getUniqId()))
            .map(v -> entity);
    }

    public Uni<Boolean> deleteByIdSafely(SqlClient client, Long id) {
        return client.preparedQuery("DELETE FROM m20OcrMerchantAlias WHERE UniqId = ?")
            .execute(Tuple.tuple().addValue(id))
            .map(result -> result.rowCount() > 0);
    }

    public Uni<Long> deleteAll(SqlClient client) {
        return client.preparedQuery("DELETE FROM m20OcrMerchantAlias")
            .execute()
            .map(result -> (long) result.rowCount());
    }

    public Uni<Long> countAll(SqlClient client) {
        return client.preparedQuery("SELECT COUNT(*) AS cnt FROM m20OcrMerchantAlias")
            .execute()
            .map(rows -> rows.iterator().next().getLong("cnt"));
    }

    private List<OcrMerchantAlias> toList(RowSet<Row> rows) {
        List<OcrMerchantAlias> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    private OcrMerchantAlias toEntity(Row row) {
        OcrMerchantAlias e = new OcrMerchantAlias();
        e.setUniqId(row.getLong("UniqId"));
        e.setOcrPattern(row.getString("OcrPattern"));
        e.setCorrectName(row.getString("CorrectName"));
        e.setConfidence(row.getInteger("Confidence"));
        e.setHitCount(row.getInteger("HitCount"));
        e.setLastUsed(row.getLocalDateTime("LastUsed"));
        e.setEntryStaff(row.getString("EntryStaff"));
        e.setEntryDate(row.getLocalDateTime("EntryDate"));
        return e;
    }
}
