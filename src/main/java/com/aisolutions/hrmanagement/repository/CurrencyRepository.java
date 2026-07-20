package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.Currency;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
@WithSession
public class CurrencyRepository implements PanacheRepositoryBase<Currency, Long> {

    /** All currencies, for the Add Receipt dropdown. */
    public Uni<List<Currency>> findAllOrdered() {
        return getSession().flatMap(session ->
            session.createQuery("FROM Currency ORDER BY currency", Currency.class)
                .getResultList()
        );
    }

    /**
     * One currency by its code (case-insensitive), or null. Used at save time to
     * resolve the rate — a missing code means the currency has no rate yet, which
     * the caller must treat as a blocked save rather than a rate of 1.
     */
    public Uni<Currency> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        return getSession().flatMap(session ->
            session.createQuery(
                    "FROM Currency WHERE upper(currency) = :code", Currency.class)
                .setParameter("code", code.trim().toUpperCase())
                .getResultList()
        ).map(list -> list.isEmpty() ? null : list.get(0));
    }
}
