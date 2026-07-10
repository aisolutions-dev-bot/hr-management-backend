package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.OcrMerchantAlias;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
@WithSession
public class OcrMerchantAliasRepository implements PanacheRepositoryBase<OcrMerchantAlias, Long> {

    public Uni<OcrMerchantAlias> findByPatternExact(String ocrPattern) {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM OcrMerchantAlias WHERE ocrPattern = :pat",
                OcrMerchantAlias.class)
                .setParameter("pat", ocrPattern)
                .getResultList()
        ).map(list -> list.isEmpty() ? null : list.get(0));
    }

    public Uni<List<OcrMerchantAlias>> findAllOrdered() {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM OcrMerchantAlias ORDER BY confidence DESC, hitCount DESC",
                OcrMerchantAlias.class)
                .getResultList()
        );
    }

    public Uni<OcrMerchantAlias> save(OcrMerchantAlias entity) {
        return getSession().flatMap(session ->
            session.persist(entity).replaceWith(entity));
    }

    public Uni<OcrMerchantAlias> update(OcrMerchantAlias entity) {
        return getSession().flatMap(session -> session.merge(entity));
    }

    public Uni<Boolean> deleteByIdSafely(Long id) {
        return getSession().flatMap(session ->
            session.find(OcrMerchantAlias.class, id)
                .onItem().ifNotNull().transformToUni(e -> session.remove(e).replaceWith(true))
                .onItem().ifNull().continueWith(false)
        );
    }

    public Uni<Long> deleteAll() {
        return getSession().flatMap(session ->
            session.createMutationQuery("DELETE FROM OcrMerchantAlias").executeUpdate()
        ).map(count -> count == null ? 0L : count.longValue());
    }

    public Uni<Long> countAll() {
        return getSession().flatMap(session ->
            session.createQuery("SELECT COUNT(a) FROM OcrMerchantAlias a", Long.class)
                .getSingleResult()
        );
    }
}
