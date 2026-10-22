package com.aisolutions.hrmanagement.repository;

import jakarta.enterprise.context.ApplicationScoped;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import org.jboss.logging.Logger;

/** Looks up company project codes in the Project module's table. */
@ApplicationScoped
public class ProjectCodeRepository {

    private static final Logger LOG = Logger.getLogger(ProjectCodeRepository.class);

    /** Reports whether a project with this code exists; an unreadable project table counts as no project. */
    public Uni<Boolean> existsByCode(SqlClient client, String projectCode) {
        return client.preparedQuery("SELECT 1 FROM m12Project WHERE ProjectCode = ? LIMIT 1")
                .execute(Tuple.of(projectCode))
                .map(rows -> rows.iterator().hasNext())
                .onFailure()
                .recoverWithItem(failure -> {
                    LOG.warnf(
                            "project_code_lookup_failed project_code=%s reason=%s", projectCode, failure.getMessage());
                    return false;
                });
    }
}
