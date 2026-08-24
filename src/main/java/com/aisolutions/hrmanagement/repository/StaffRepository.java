package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.dto.DropdownOptionDTO;
import com.aisolutions.hrmanagement.entity.Staff;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Slf4j
public class StaffRepository {

    /**
     * The staff member's display name, or null when the id is blank or unknown in
     * m03Staff. Callers fall back to the raw staffId so a missing name never blanks
     * a notification message.
     */
    public Uni<String> findNameByStaffId(SqlClient client, String staffId) {
        if (staffId == null || staffId.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        return client.preparedQuery("SELECT Name FROM m03Staff WHERE StaffId = ?")
            .execute(Tuple.tuple().addValue(staffId))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next().getString("Name") : null);
    }

    /**
     * The staff member's record (name, department, join date), or null when unknown.
     * Used to prefill the leave wizard's Step 1 and to compute leave entitlement.
     */
    public Uni<Staff> findByStaffId(SqlClient client, String staffId) {
        if (staffId == null || staffId.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        return client.preparedQuery(
                "SELECT Code, StaffId, Name, Department, DateJoin, SystemUser, Status " +
                "FROM m03Staff WHERE StaffId = ? LIMIT 1")
            .execute(Tuple.tuple().addValue(staffId))
            .map(rows -> rows.iterator().hasNext() ? toEntity(rows.iterator().next()) : null);
    }

    /**
     * Approver dropdown options (value = staffId, label = "Name (staffId)").
     * Only login-capable, active staff can act on an approval, so the list is limited to
     * SystemUser='Y' and non-terminated status; the SUPERDREW superadmin account is excluded.
     */
    public Uni<List<DropdownOptionDTO>> findApproverOptions(SqlClient client) {
        return client.preparedQuery(
                "SELECT StaffId, Name FROM m03Staff " +
                "WHERE StaffId IS NOT NULL AND StaffId <> '' " +
                "AND UPPER(SystemUser) = 'Y' " +
                "AND (Status IS NULL OR Status <> 'T') " +
                "AND UPPER(StaffId) <> 'SUPERDREW' " +
                "ORDER BY Name")
            .execute()
            .map(rows -> {
                List<DropdownOptionDTO> result = new ArrayList<>();
                for (Row row : rows) {
                    String staffId = row.getString("StaffId");
                    String name = row.getString("Name");
                    String label = (name != null && !name.isBlank())
                            ? name + " (" + staffId + ")"
                            : staffId;
                    result.add(new DropdownOptionDTO(staffId, label));
                }
                return result;
            });
    }

    private Staff toEntity(Row row) {
        Staff s = new Staff();
        s.setCode(row.getLong("Code"));
        s.setStaffId(row.getString("StaffId"));
        s.setName(row.getString("Name"));
        s.setDepartment(row.getString("Department"));
        s.setDateJoin(row.getLocalDateTime("DateJoin"));
        s.setSystemUser(row.getString("SystemUser"));
        s.setStatus(row.getString("Status"));
        return s;
    }
}
