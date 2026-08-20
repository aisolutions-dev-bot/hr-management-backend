package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.dto.DropdownOptionDTO;
import com.aisolutions.hrmanagement.entity.Staff;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
@WithSession
public class StaffRepository implements PanacheRepositoryBase<Staff, Long> {

    /**
     * The staff member's display name, or null when the id is blank or unknown in
     * m03Staff. Callers fall back to the raw staffId so a missing name never blanks
     * a notification message.
     */
    public Uni<String> findNameByStaffId(String staffId) {
        if (staffId == null || staffId.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        return getSession().flatMap(session ->
            session.createQuery("SELECT s.name FROM Staff s WHERE s.staffId = :id", String.class)
                .setParameter("id", staffId)
                .getResultList()
                .map(names -> names.isEmpty() ? null : names.get(0)));
    }

    /**
     * The staff member's record (name, department, join date), or null when unknown.
     * Used to prefill the leave wizard's Step 1 and to compute leave entitlement.
     */
    public Uni<Staff> findByStaffId(String staffId) {
        if (staffId == null || staffId.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        return getSession().flatMap(session ->
            session.createQuery("FROM Staff WHERE staffId = :id", Staff.class)
                .setParameter("id", staffId)
                .setMaxResults(1)
                .getResultList()
        ).map(list -> list.isEmpty() ? null : list.get(0));
    }

    /**
     * Approver dropdown options (value = staffId, label = "Name (staffId)").
     * Only login-capable, active staff can act on an approval, so the list is limited to
     * SystemUser='Y' and non-terminated status; the SUPERDREW superadmin account is excluded.
     */
    public Uni<List<DropdownOptionDTO>> findApproverOptions() {
        return getSession().flatMap(session ->
            session.createQuery(
                "SELECT new com.aisolutions.hrmanagement.dto.DropdownOptionDTO("
                    + "s.staffId, CONCAT(COALESCE(s.name, s.staffId), ' (', s.staffId, ')')) "
                    + "FROM Staff s WHERE s.staffId IS NOT NULL AND s.staffId <> '' "
                    + "AND UPPER(s.systemUser) = 'Y' "
                    + "AND (s.status IS NULL OR s.status <> 'T') "
                    + "AND UPPER(s.staffId) <> 'SUPERDREW' "
                    + "ORDER BY s.name",
                DropdownOptionDTO.class)
                .getResultList());
    }
}
