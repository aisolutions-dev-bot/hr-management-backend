package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.SystemParameter;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reactive Panache repository for m07SystemParameters.
 *
 * Loads all requested parameters in a single query to avoid multiple
 * round-trips and session-context conflicts with parallel Uni subscriptions.
 */
@ApplicationScoped
@WithSession
public class SystemParameterRepository implements PanacheRepositoryBase<SystemParameter, Long> {

    /**
     * Fetch multiple parameters at once and return them as a name→value map.
     * Missing keys are absent from the returned map (caller must handle nulls).
     */
    public Uni<Map<String, String>> getParameterMap(List<String> parameters) {
        return getSession().flatMap(session ->
            session.createQuery(
                    "SELECT p FROM SystemParameter p WHERE p.parameter IN :params",
                    SystemParameter.class)
                .setParameter("params", parameters)
                .getResultList()
        ).map(list ->
            list.stream().collect(Collectors.toMap(
                p -> p.getParameter(),
                p -> p.getParameterValue() != null ? p.getParameterValue() : ""
            ))
        );
    }
}
