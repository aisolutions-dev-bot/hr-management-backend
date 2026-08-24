package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.Currency;

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
public class CurrencyRepository {

    /** All currencies, for the Add Receipt dropdown. */
    public Uni<List<Currency>> findAllOrdered(SqlClient client) {
        return client.preparedQuery(
                "SELECT UniqId, Currency, CurrencyDesc, ExchangeRate FROM m01Currency ORDER BY Currency")
            .execute()
            .map(this::toList);
    }

    /**
     * One currency by its code (case-insensitive), or null.
     */
    public Uni<Currency> findByCode(SqlClient client, String code) {
        if (code == null || code.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        return client.preparedQuery(
                "SELECT UniqId, Currency, CurrencyDesc, ExchangeRate FROM m01Currency WHERE UPPER(Currency) = ?")
            .execute(Tuple.tuple().addValue(code.trim().toUpperCase()))
            .map(rows -> rows.iterator().hasNext() ? toEntity(rows.iterator().next()) : null);
    }

    private List<Currency> toList(RowSet<Row> rows) {
        List<Currency> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    private Currency toEntity(Row row) {
        Currency c = new Currency();
        c.setUniqId(row.getLong("UniqId"));
        c.setCurrency(row.getString("Currency"));
        c.setCurrencyDesc(row.getString("CurrencyDesc"));
        c.setExchangeRate(row.getBigDecimal("ExchangeRate"));
        return c;
    }
}
