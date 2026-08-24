package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.OcrGlobalFieldRule;

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
public class OcrGlobalFieldRuleRepository {

    public Uni<OcrGlobalFieldRule> findByFieldAndKeyword(SqlClient client, String fieldName, String keyword) {
        return client.preparedQuery(
                "SELECT UniqId, FieldName, Keyword, ValuePattern, DateFormat, Confidence, " +
                "HitCount, ConfirmedByCount, LastUsed, EntryDate " +
                "FROM m20OcrGlobalFieldRule WHERE FieldName = ? AND Keyword = ?")
            .execute(Tuple.tuple().addValue(fieldName).addValue(keyword))
            .map(rows -> rows.iterator().hasNext() ? toEntity(rows.iterator().next()) : null);
    }

    public Uni<List<OcrGlobalFieldRule>> findByFieldName(SqlClient client, String fieldName) {
        return client.preparedQuery(
                "SELECT UniqId, FieldName, Keyword, ValuePattern, DateFormat, Confidence, " +
                "HitCount, ConfirmedByCount, LastUsed, EntryDate " +
                "FROM m20OcrGlobalFieldRule WHERE FieldName = ? " +
                "ORDER BY Confidence DESC, ConfirmedByCount DESC, HitCount DESC")
            .execute(Tuple.tuple().addValue(fieldName))
            .map(this::toList);
    }

    public Uni<List<OcrGlobalFieldRule>> findAllOrdered(SqlClient client) {
        return client.preparedQuery(
                "SELECT UniqId, FieldName, Keyword, ValuePattern, DateFormat, Confidence, " +
                "HitCount, ConfirmedByCount, LastUsed, EntryDate " +
                "FROM m20OcrGlobalFieldRule ORDER BY FieldName, Confidence DESC")
            .execute()
            .map(this::toList);
    }

    public Uni<OcrGlobalFieldRule> save(SqlClient client, OcrGlobalFieldRule entity) {
        return client.preparedQuery(
                "INSERT INTO m20OcrGlobalFieldRule (FieldName, Keyword, ValuePattern, DateFormat, " +
                "Confidence, HitCount, ConfirmedByCount, LastUsed, EntryDate) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .execute(Tuple.tuple()
                .addValue(entity.getFieldName())
                .addValue(entity.getKeyword())
                .addValue(entity.getValuePattern())
                .addValue(entity.getDateFormat())
                .addValue(entity.getConfidence())
                .addValue(entity.getHitCount())
                .addValue(entity.getConfirmedByCount())
                .addValue(entity.getLastUsed())
                .addValue(entity.getEntryDate()))
            .flatMap(result -> {
                Long id = result.property(MySQLClient.LAST_INSERTED_ID);
                entity.setUniqId(id);
                return Uni.createFrom().item(entity);
            });
    }

    public Uni<OcrGlobalFieldRule> update(SqlClient client, OcrGlobalFieldRule entity) {
        return client.preparedQuery(
                "UPDATE m20OcrGlobalFieldRule SET FieldName = ?, Keyword = ?, ValuePattern = ?, " +
                "DateFormat = ?, Confidence = ?, HitCount = ?, ConfirmedByCount = ?, " +
                "LastUsed = ?, EntryDate = ? WHERE UniqId = ?")
            .execute(Tuple.tuple()
                .addValue(entity.getFieldName())
                .addValue(entity.getKeyword())
                .addValue(entity.getValuePattern())
                .addValue(entity.getDateFormat())
                .addValue(entity.getConfidence())
                .addValue(entity.getHitCount())
                .addValue(entity.getConfirmedByCount())
                .addValue(entity.getLastUsed())
                .addValue(entity.getEntryDate())
                .addValue(entity.getUniqId()))
            .map(v -> entity);
    }

    public Uni<Boolean> deleteByIdSafely(SqlClient client, Long id) {
        return client.preparedQuery("DELETE FROM m20OcrGlobalFieldRule WHERE UniqId = ?")
            .execute(Tuple.tuple().addValue(id))
            .map(result -> result.rowCount() > 0);
    }

    public Uni<Long> deleteAll(SqlClient client) {
        return client.preparedQuery("DELETE FROM m20OcrGlobalFieldRule")
            .execute()
            .map(result -> (long) result.rowCount());
    }

    public Uni<Long> countAll(SqlClient client) {
        return client.preparedQuery("SELECT COUNT(*) AS cnt FROM m20OcrGlobalFieldRule")
            .execute()
            .map(rows -> rows.iterator().next().getLong("cnt"));
    }

    private List<OcrGlobalFieldRule> toList(RowSet<Row> rows) {
        List<OcrGlobalFieldRule> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    private OcrGlobalFieldRule toEntity(Row row) {
        OcrGlobalFieldRule e = new OcrGlobalFieldRule();
        e.setUniqId(row.getLong("UniqId"));
        e.setFieldName(row.getString("FieldName"));
        e.setKeyword(row.getString("Keyword"));
        e.setValuePattern(row.getString("ValuePattern"));
        e.setDateFormat(row.getString("DateFormat"));
        e.setConfidence(row.getInteger("Confidence"));
        e.setHitCount(row.getInteger("HitCount"));
        e.setConfirmedByCount(row.getInteger("ConfirmedByCount"));
        e.setLastUsed(row.getLocalDateTime("LastUsed"));
        e.setEntryDate(row.getLocalDateTime("EntryDate"));
        return e;
    }
}
