package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.LeaveApplication;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
@WithSession
public class LeaveApplicationRepository implements PanacheRepositoryBase<LeaveApplication, Long> {

    public Uni<LeaveApplication> save(LeaveApplication entity) {
        return getSession().flatMap(session -> session.persist(entity).replaceWith(entity));
    }

    public Uni<LeaveApplication> update(LeaveApplication entity) {
        return getSession().flatMap(session -> session.merge(entity));
    }

    /** A staff member's leave applications, newest first. */
    public Uni<List<LeaveApplication>> findByStaff(String staffId) {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM LeaveApplication WHERE staffId = :staffId ORDER BY uniqId DESC",
                LeaveApplication.class)
                .setParameter("staffId", staffId)
                .getResultList());
    }

    /**
     * The staff member's own APPLY leaves of a type that are eligible to cancel — i.e.
     * currently APPROVED (a cancel targets an approved leave to give the days back).
     * Newest period first.
     */
    public Uni<List<LeaveApplication>> findCancelable(String staffId, String leaveType) {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM LeaveApplication WHERE staffId = :staffId AND leaveType = :lt "
                    + "AND leaveAction = 'APPLY' AND status = 'APPROVED' "
                    + "ORDER BY fromDate DESC, uniqId DESC",
                LeaveApplication.class)
                .setParameter("staffId", staffId)
                .setParameter("lt", leaveType)
                .getResultList());
    }

    /**
     * Total leave days the staff member has already committed for a leave type whose
     * leave FALLS IN the given date window (by FromDate) — APPLY rows that are still
     * PENDING or APPROVED. REJECTED and CANCELLED rows are excluded, so a cancelled
     * leave frees its days back into the balance. Used for the balance check.
     */
    public Uni<BigDecimal> sumBookedDays(String staffId, String leaveType,
                                         LocalDate windowStart, LocalDate windowEnd) {
        return getSession().flatMap(session ->
            session.createQuery(
                "SELECT SUM(l.totalDays) FROM LeaveApplication l "
                    + "WHERE l.staffId = :staffId AND l.leaveType = :lt "
                    + "AND l.leaveAction = 'APPLY' AND l.status IN ('PENDING', 'APPROVED') "
                    + "AND l.fromDate >= :start AND l.fromDate <= :end",
                BigDecimal.class)
                .setParameter("staffId", staffId)
                .setParameter("lt", leaveType)
                .setParameter("start", windowStart)
                .setParameter("end", windowEnd)
                .getResultList()
        ).map(list -> (list.isEmpty() || list.get(0) == null) ? BigDecimal.ZERO : list.get(0));
    }

    /** Booked APPLY days grouped by leave type and status (PENDING/APPROVED) within the window.
     *  Each row is {@code [leaveType, status, SUM(totalDays)]}. */
    public Uni<List<Object[]>> sumBookedDaysByTypeAndStatus(String staffId,
                                                            LocalDate windowStart, LocalDate windowEnd) {
        return getSession().flatMap(session ->
            session.createQuery(
                "SELECT l.leaveType, l.status, SUM(l.totalDays) FROM LeaveApplication l "
                    + "WHERE l.staffId = :staffId "
                    + "AND l.leaveAction = 'APPLY' AND l.status IN ('PENDING', 'APPROVED') "
                    + "AND l.fromDate >= :start AND l.fromDate <= :end "
                    + "GROUP BY l.leaveType, l.status",
                Object[].class)
                .setParameter("staffId", staffId)
                .setParameter("start", windowStart)
                .setParameter("end", windowEnd)
                .getResultList());
    }
}
