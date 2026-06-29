package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.StaffClaim;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
@WithSession
public class StaffClaimRepository implements PanacheRepositoryBase<StaffClaim, Long> {

    public Uni<List<StaffClaim>> findByStaff(String staffId) {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM StaffClaim WHERE staffId = :staffId ORDER BY uniqId DESC",
                StaffClaim.class)
                .setParameter("staffId", staffId)
                .getResultList()
        );
    }

    public Uni<StaffClaim> save(StaffClaim entity) {
        return getSession().flatMap(session ->
            session.persist(entity).replaceWith(entity));
    }

    public Uni<StaffClaim> update(StaffClaim entity) {
        return getSession().flatMap(session -> session.merge(entity));
    }
}
