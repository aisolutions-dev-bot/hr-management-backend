package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.StaffClaimDetail;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
@WithSession
public class StaffClaimDetailRepository implements PanacheRepositoryBase<StaffClaimDetail, Long> {

    public Uni<List<StaffClaimDetail>> findByStaffAndDateRange(
            String staffId, LocalDateTime from, LocalDateTime to) {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM StaffClaimDetail WHERE staffId = :staffId " +
                "AND claimDate >= :from AND claimDate < :to " +
                "ORDER BY claimDate DESC",
                StaffClaimDetail.class)
                .setParameter("staffId", staffId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList()
        );
    }

    public Uni<List<StaffClaimDetail>> findByStaff(String staffId) {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM StaffClaimDetail WHERE staffId = :staffId ORDER BY claimDate DESC",
                StaffClaimDetail.class)
                .setParameter("staffId", staffId)
                .getResultList()
        );
    }

    /** Line counts grouped by header, for a set of header ids. Returns [headerId, count] rows. */
    public Uni<List<Object[]>> countByHeaderIds(List<Long> headerIds) {
        if (headerIds == null || headerIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return getSession().flatMap(session ->
            session.createQuery(
                "SELECT claimId, COUNT(uniqId) FROM StaffClaimDetail " +
                "WHERE claimId IN (:ids) GROUP BY claimId",
                Object[].class)
                .setParameter("ids", headerIds)
                .getResultList()
        );
    }

    /** All line items belonging to a claim header. */
    public Uni<List<StaffClaimDetail>> findByHeaderId(Long claimId) {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM StaffClaimDetail WHERE claimId = :hid ORDER BY claimDate ASC, uniqId ASC",
                StaffClaimDetail.class)
                .setParameter("hid", claimId)
                .getResultList()
        );
    }

    /**
     * The claim id owning the staff member's receipt with this number, or null. Used to
     * deep-link a rejection notification (which carries the receipt number) to its claim.
     */
    public Uni<Long> findClaimIdByStaffAndReceiptNumber(String staffId, String receiptNumber) {
        return getSession().flatMap(session ->
            session.createQuery(
                "SELECT d.claimId FROM StaffClaimDetail d WHERE d.staffId = :staffId "
                    + "AND d.receiptNumber = :rn ORDER BY d.uniqId DESC",
                Long.class)
                .setParameter("staffId", staffId)
                .setParameter("rn", receiptNumber)
                .setMaxResults(1)
                .getResultList()
        ).map(list -> list.isEmpty() ? null : list.get(0));
    }

    public Uni<StaffClaimDetail> save(StaffClaimDetail entity) {
        return getSession().flatMap(session ->
            session.persist(entity).replaceWith(entity));
    }

    public Uni<StaffClaimDetail> update(StaffClaimDetail entity) {
        return getSession().flatMap(session -> session.merge(entity));
    }
}
