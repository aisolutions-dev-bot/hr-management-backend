package com.aisolutions.hrmanagement.repository;

import jakarta.enterprise.context.ApplicationScoped;

import com.aisolutions.shared.util.DateUtil;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;

/**
 * Access to the document-number sequence tables {@code m01DocNoSeq} (definition)
 * and {@code m01DocNoSeqDet} (running counter per Year + Month). Writes run inside
 * the caller's transaction so the counter and the document commit together.
 */
@ApplicationScoped
public class DocNoSeqRepository {

    /** The sequence definition; {@code uniqId} is the {@code RefUniqId} the detail rows point at. */
    public record SeqDef(long uniqId, String prefix, String format, int digit) {}

    /** One counter row: the definition plus its Year and Month buckets (empty when the format has none). */
    public record Bucket(long refUniqId, String year, String month) {}

    /** Locks and returns the definition for a module + screen, or null when not seeded. */
    public Uni<SeqDef> findDefinitionForUpdate(SqlClient client, String moduleId, String screenType) {
        return client.preparedQuery("SELECT UniqId, Prefix, Format, Digit FROM m01DocNoSeq "
                        + "WHERE ModuleId = ? AND ScreenType = ? FOR UPDATE")
                .execute(Tuple.of(moduleId, screenType))
                .map(rows -> {
                    if (!rows.iterator().hasNext()) {
                        return null;
                    }
                    Row row = rows.iterator().next();
                    Integer digit = row.getInteger("Digit");
                    return new SeqDef(
                            row.getLong("UniqId"),
                            row.getString("Prefix"),
                            row.getString("Format"),
                            digit == null ? 6 : digit);
                });
    }

    /** The last sequence number issued for a bucket, or 0 when none yet. */
    public Uni<Long> findLastSeq(SqlClient client, Bucket bucket) {
        return client.preparedQuery(
                        "SELECT LastSeqNo FROM m01DocNoSeqDet WHERE RefUniqId = ? AND Year = ? AND Month = ?")
                .execute(Tuple.of(bucket.refUniqId(), bucket.year(), bucket.month()))
                .map(rows ->
                        rows.iterator().hasNext() ? parse(rows.iterator().next().getString("LastSeqNo")) : 0L);
    }

    /** Advances the counter to {@code seq}: updates the bucket's row, or inserts it for the first number. */
    public Uni<Void> saveSeq(SqlClient client, Bucket bucket, long seq, String actor) {
        var now = DateUtil.nowSGT();
        return client.preparedQuery("UPDATE m01DocNoSeqDet SET LastSeqNo = ?, LastEditStaff = ?, LastEditDate = ? "
                        + "WHERE RefUniqId = ? AND Year = ? AND Month = ?")
                .execute(Tuple.tuple()
                        .addValue(Long.toString(seq))
                        .addValue(actor)
                        .addValue(now)
                        .addValue(bucket.refUniqId())
                        .addValue(bucket.year())
                        .addValue(bucket.month()))
                .flatMap(result -> result.rowCount() > 0
                        ? Uni.createFrom().voidItem()
                        : client.preparedQuery("INSERT INTO m01DocNoSeqDet "
                                        + "(EntryStaff, EntryDate, RefUniqId, Year, Month, LastSeqNo) "
                                        + "VALUES (?, ?, ?, ?, ?, ?)")
                                .execute(Tuple.tuple()
                                        .addValue(actor)
                                        .addValue(now)
                                        .addValue(bucket.refUniqId())
                                        .addValue(bucket.year())
                                        .addValue(bucket.month())
                                        .addValue(Long.toString(seq)))
                                .replaceWithVoid());
    }

    private long parse(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
