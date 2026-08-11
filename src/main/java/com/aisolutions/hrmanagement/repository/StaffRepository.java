package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.Staff;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

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
}
