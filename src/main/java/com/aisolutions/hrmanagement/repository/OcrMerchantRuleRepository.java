package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.OcrMerchantRule;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
@WithSession
public class OcrMerchantRuleRepository implements PanacheRepositoryBase<OcrMerchantRule, Long> {

    public Uni<OcrMerchantRule> findByMerchantExact(String merchantName) {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM OcrMerchantRule WHERE merchantName = :name",
                OcrMerchantRule.class)
                .setParameter("name", merchantName)
                .getResultList()
        ).map(list -> list.isEmpty() ? null : list.get(0));
    }

    public Uni<List<OcrMerchantRule>> findAllOrdered() {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM OcrMerchantRule ORDER BY confidence DESC, hitCount DESC",
                OcrMerchantRule.class)
                .getResultList()
        );
    }

    public Uni<OcrMerchantRule> save(OcrMerchantRule entity) {
        return getSession().flatMap(session ->
            session.persist(entity).replaceWith(entity));
    }

    public Uni<OcrMerchantRule> update(OcrMerchantRule entity) {
        return getSession().flatMap(session -> session.merge(entity));
    }

    public Uni<Boolean> deleteByIdSafely(Long id) {
        return getSession().flatMap(session ->
            session.find(OcrMerchantRule.class, id)
                .onItem().ifNotNull().transformToUni(e -> session.remove(e).replaceWith(true))
                .onItem().ifNull().continueWith(false)
        );
    }

    public Uni<Long> deleteAll() {
        return getSession().flatMap(session ->
            session.createMutationQuery("DELETE FROM OcrMerchantRule").executeUpdate()
        ).map(count -> count == null ? 0L : count.longValue());
    }

    public Uni<Long> countAll() {
        return getSession().flatMap(session ->
            session.createQuery("SELECT COUNT(r) FROM OcrMerchantRule r", Long.class)
                .getSingleResult()
        );
    }
}
