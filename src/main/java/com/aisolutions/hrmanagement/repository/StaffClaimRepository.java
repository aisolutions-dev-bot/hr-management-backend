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

    /**
     * The staff member's header for a period in a given status, or null.
     *
     * Returns the newest match rather than asserting uniqueness: nothing in the
     * schema stops a staff member having two DRAFT headers for one period, and
     * live data already contains such duplicates from before auto-create existed.
     */
    public Uni<StaffClaim> findByStaffPeriodStatus(String staffId, String period, String status) {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM StaffClaim WHERE staffId = :staffId AND claimPeriod = :period "
                    + "AND status = :status ORDER BY uniqId DESC",
                StaffClaim.class)
                .setParameter("staffId", staffId)
                .setParameter("period", period)
                .setParameter("status", status)
                .setMaxResults(1)
                .getResultList()
        ).map(list -> list.isEmpty() ? null : list.get(0));
    }

    /**
     * This staff's numbered periods under a base month (base "JULY-2026" matches
     * "JULY-2026-001"…); the plain draft "JULY-2026" is excluded by the trailing dash.
     */
    public Uni<List<String>> findPeriodsWithSuffix(String staffId, String basePeriod) {
        return getSession().flatMap(session ->
            session.createQuery(
                "SELECT claimPeriod FROM StaffClaim WHERE staffId = :staffId "
                    + "AND claimPeriod LIKE :like", String.class)
                .setParameter("staffId", staffId)
                .setParameter("like", basePeriod + "-%")
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
