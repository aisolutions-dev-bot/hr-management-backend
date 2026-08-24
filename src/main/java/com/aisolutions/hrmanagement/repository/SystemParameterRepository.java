package com.aisolutions.hrmanagement.repository;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Raw SqlClient access for m07SystemParameters.
 *
 * Loads all requested parameters in a single query to avoid multiple
 * round-trips.
 */
@ApplicationScoped
@Slf4j
public class SystemParameterRepository {

    /**
     * Fetch multiple parameters at once and return them as a name-to-value map.
     * Missing keys are absent from the returned map (caller must handle nulls).
     */
    public Uni<Map<String, String>> getParameterMap(SqlClient client, List<String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        String placeholders = String.join(", ", parameters.stream().map(s -> "?").toList());
        return client.preparedQuery(
                "SELECT Parameter, ParameterValue FROM m07SystemParameters WHERE Parameter IN (" + placeholders + ")")
            .execute(tupleOf(parameters))
            .map(rows -> {
                Map<String, String> result = new HashMap<>();
                for (Row row : rows) {
                    String key = row.getString("Parameter");
                    String value = row.getString("ParameterValue");
                    result.put(key, value != null ? value : "");
                }
                return result;
            });
    }

    private Tuple tupleOf(List<String> values) {
        Tuple tuple = Tuple.tuple();
        for (String value : values) {
            tuple.addValue(value);
        }
        return tuple;
    }
}
