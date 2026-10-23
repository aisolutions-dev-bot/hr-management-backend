package com.aisolutions.hrmanagement.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;

/**
 * Read-only access to the active claim types (m18HRClaimType) and default rules (m18HRSetting).
 */
@ApplicationScoped
public class ClaimSettingsRepository {

    /** Active claim type names in dropdown order. */
    public Uni<List<String>> findActiveClaimTypes(SqlClient client) {
        return client.preparedQuery("SELECT ClaimType FROM m18HRClaimType WHERE IsActive = 1 "
                        + "ORDER BY SortOrder ASC, UniqId ASC")
                .execute()
                .map(rows -> {
                    List<String> out = new ArrayList<>();
                    for (Row r : rows) {
                        out.add(r.getString("ClaimType"));
                    }
                    return out;
                });
    }

    /** Active default rules, first match wins, as {conditionField, operator, conditionValue, targetField, defaultValue} in rule order. */
    public Uni<List<Map<String, String>>> findActiveRules(SqlClient client) {
        return client.preparedQuery("SELECT ConditionField, Operator, ConditionValue, TargetField, DefaultValue FROM m18HRSetting WHERE ScreenType = ? AND IsActive = 1 "
                        + "ORDER BY RuleOrder ASC, UniqId ASC")
                .execute(Tuple.of("Claim Application"))
                .map(rows -> {
                    List<Map<String, String>> out = new ArrayList<>();
                    for (Row r : rows) {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("conditionField", r.getString("ConditionField"));
                        m.put("operator", r.getString("Operator"));
                        m.put("conditionValue", r.getString("ConditionValue"));
                        m.put("targetField", r.getString("TargetField"));
                        m.put("defaultValue", r.getString("DefaultValue"));
                        out.add(m);
                    }
                    return out;
                });
    }
}
