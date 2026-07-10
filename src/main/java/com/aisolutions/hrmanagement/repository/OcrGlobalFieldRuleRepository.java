package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.OcrGlobalFieldRule;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
@WithSession
public class OcrGlobalFieldRuleRepository implements PanacheRepositoryBase<OcrGlobalFieldRule, Long> {

    public Uni<OcrGlobalFieldRule> findByFieldAndKeyword(String fieldName, String keyword) {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM OcrGlobalFieldRule WHERE fieldName = :field AND keyword = :kw",
                OcrGlobalFieldRule.class)
                .setParameter("field", fieldName)
                .setParameter("kw", keyword)
                .getResultList()
        ).map(list -> list.isEmpty() ? null : list.get(0));
    }

    public Uni<List<OcrGlobalFieldRule>> findByFieldName(String fieldName) {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM OcrGlobalFieldRule WHERE fieldName = :field " +
                "ORDER BY confidence DESC, confirmedByCount DESC, hitCount DESC",
                OcrGlobalFieldRule.class)
                .setParameter("field", fieldName)
                .getResultList()
        );
    }

    public Uni<List<OcrGlobalFieldRule>> findAllOrdered() {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM OcrGlobalFieldRule ORDER BY fieldName, confidence DESC",
                OcrGlobalFieldRule.class)
                .getResultList()
        );
    }

    public Uni<OcrGlobalFieldRule> save(OcrGlobalFieldRule entity) {
        return getSession().flatMap(session ->
            session.persist(entity).replaceWith(entity));
    }

    public Uni<OcrGlobalFieldRule> update(OcrGlobalFieldRule entity) {
        return getSession().flatMap(session -> session.merge(entity));
    }

    public Uni<Boolean> deleteByIdSafely(Long id) {
        return getSession().flatMap(session ->
            session.find(OcrGlobalFieldRule.class, id)
                .onItem().ifNotNull().transformToUni(e -> session.remove(e).replaceWith(true))
                .onItem().ifNull().continueWith(false)
        );
    }

    public Uni<Long> deleteAll() {
        return getSession().flatMap(session ->
            session.createMutationQuery("DELETE FROM OcrGlobalFieldRule").executeUpdate()
        ).map(count -> count == null ? 0L : count.longValue());
    }

    public Uni<Long> countAll() {
        return getSession().flatMap(session ->
            session.createQuery("SELECT COUNT(g) FROM OcrGlobalFieldRule g", Long.class)
                .getSingleResult()
        );
    }
}
