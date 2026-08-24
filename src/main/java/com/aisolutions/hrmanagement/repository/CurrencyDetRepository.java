package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.CurrencyDet;

import io.smallrye.mutiny.Uni;
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
public class CurrencyDetRepository {

    /**
     * The month-rate row for one currency, or null when it has none.
     */
    public Uni<CurrencyDet> findByCurrencyId(SqlClient client, Long refUniqId) {
        if (refUniqId == null) {
            return Uni.createFrom().nullItem();
        }
        return client.preparedQuery(
                "SELECT UniqId, RefUniqId, ExRate1, ExRate2, ExRate3, ExRate4, ExRate5, ExRate6, " +
                "ExRate7, ExRate8, ExRate9, ExRate10, ExRate11, ExRate12 " +
                "FROM m01CurrencyDet WHERE RefUniqId = ? ORDER BY UniqId LIMIT 1")
            .execute(Tuple.tuple().addValue(refUniqId))
            .map(rows -> rows.iterator().hasNext() ? toEntity(rows.iterator().next()) : null);
    }

    /** All currency detail rows (for the rates-with-currencies listing). */
    public Uni<List<CurrencyDet>> listAll(SqlClient client) {
        return client.preparedQuery(
                "SELECT UniqId, RefUniqId, ExRate1, ExRate2, ExRate3, ExRate4, ExRate5, ExRate6, " +
                "ExRate7, ExRate8, ExRate9, ExRate10, ExRate11, ExRate12 " +
                "FROM m01CurrencyDet ORDER BY UniqId")
            .execute()
            .map(this::toList);
    }

    private List<CurrencyDet> toList(RowSet<Row> rows) {
        List<CurrencyDet> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    private CurrencyDet toEntity(Row row) {
        CurrencyDet d = new CurrencyDet();
        d.setUniqId(row.getLong("UniqId"));
        d.setRefUniqId(row.getLong("RefUniqId"));
        d.setExRate1(row.getBigDecimal("ExRate1"));
        d.setExRate2(row.getBigDecimal("ExRate2"));
        d.setExRate3(row.getBigDecimal("ExRate3"));
        d.setExRate4(row.getBigDecimal("ExRate4"));
        d.setExRate5(row.getBigDecimal("ExRate5"));
        d.setExRate6(row.getBigDecimal("ExRate6"));
        d.setExRate7(row.getBigDecimal("ExRate7"));
        d.setExRate8(row.getBigDecimal("ExRate8"));
        d.setExRate9(row.getBigDecimal("ExRate9"));
        d.setExRate10(row.getBigDecimal("ExRate10"));
        d.setExRate11(row.getBigDecimal("ExRate11"));
        d.setExRate12(row.getBigDecimal("ExRate12"));
        return d;
    }
}
