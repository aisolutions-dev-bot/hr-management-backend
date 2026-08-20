package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.dto.DropdownOptionDTO;
import com.aisolutions.hrmanagement.entity.LeaveType;
import com.aisolutions.hrmanagement.entity.LeaveTypeEntitlement;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Read-only access to the leave master data owned by General Settings:
 * m01LeaveType (the Leave Type dropdown) and m01LeaveTypeEntitlement (the per-year
 * entitlement bands used to compute a staff member's leave balance).
 */
@ApplicationScoped
@WithSession
public class LeaveTypeRepository implements PanacheRepositoryBase<LeaveType, Long> {

    /** Leave types as dropdown options (value = code, label = description). */
    public Uni<List<DropdownOptionDTO>> findAllOptions() {
        return getSession().flatMap(session ->
            session.createQuery(
                "SELECT new com.aisolutions.hrmanagement.dto.DropdownOptionDTO("
                    + "t.leaveType, COALESCE(t.description, t.leaveType)) "
                    + "FROM LeaveType t ORDER BY t.leaveType",
                DropdownOptionDTO.class)
                .getResultList());
    }

    /** Description for one leave-type code, or null when unknown. */
    public Uni<String> findDescription(String leaveType) {
        if (leaveType == null || leaveType.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        return getSession().flatMap(session ->
            session.createQuery(
                "SELECT t.description FROM LeaveType t WHERE t.leaveType = :lt", String.class)
                .setParameter("lt", leaveType)
                .getResultList()
        ).map(list -> list.isEmpty() ? null : list.get(0));
    }

    /** Entitlement bands for a leave type, ascending by YearOfService (empty when none). */
    public Uni<List<LeaveTypeEntitlement>> findEntitlements(String leaveType) {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM LeaveTypeEntitlement WHERE leaveType = :lt ORDER BY yearOfService ASC",
                LeaveTypeEntitlement.class)
                .setParameter("lt", leaveType)
                .getResultList());
    }

    /** Every entitlement band, ordered by leave type then YearOfService ascending —
     *  one query feeding the all-types balance summary (grouped in the service). */
    public Uni<List<LeaveTypeEntitlement>> findAllEntitlements() {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM LeaveTypeEntitlement ORDER BY leaveType ASC, yearOfService ASC",
                LeaveTypeEntitlement.class)
                .getResultList());
    }
}
