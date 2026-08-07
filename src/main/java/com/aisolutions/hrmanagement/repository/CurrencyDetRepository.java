package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.CurrencyDet;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@WithSession
public class CurrencyDetRepository implements PanacheRepositoryBase<CurrencyDet, Long> {

    /**
     * The month-rate row for one currency, or null when it has none — normal, and
     * means only the header rate exists. RefUniqId has no unique constraint, so
     * ordering by UniqId keeps the pick deterministic.
     */
    public Uni<CurrencyDet> findByCurrencyId(Long refUniqId) {
        if (refUniqId == null) {
            return Uni.createFrom().nullItem();
        }
        return getSession().flatMap(session ->
            session.createQuery(
                    "FROM CurrencyDet WHERE refUniqId = :ref ORDER BY uniqId", CurrencyDet.class)
                .setParameter("ref", refUniqId)
                .getResultList()
        ).map(list -> list.isEmpty() ? null : list.get(0));
    }
}
