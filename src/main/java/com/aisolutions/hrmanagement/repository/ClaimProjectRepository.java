package com.aisolutions.hrmanagement.repository;

import jakarta.enterprise.context.ApplicationScoped;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;

/** Reads whether the company subscribes to the Project module, which gates the claim title project picker. */
@ApplicationScoped
public class ClaimProjectRepository {

    private static final String PROJECT_MODULE_ID = "mod12";

    /** True when the company's subscription includes the Project module. */
    public Uni<Boolean> isProjectModuleSubscribed(SqlClient client) {
        return client.preparedQuery("SELECT 1 FROM m01SubscriptionModule "
                        + "WHERE ModuleId = ? AND CAST(ModuleAccessValue AS SIGNED) = 1 LIMIT 1")
                .execute(Tuple.of(PROJECT_MODULE_ID))
                .map(rows -> rows.iterator().hasNext());
    }
}
