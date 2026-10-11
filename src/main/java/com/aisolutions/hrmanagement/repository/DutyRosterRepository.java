package com.aisolutions.hrmanagement.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import com.aisolutions.hrmanagement.entity.DutyRoster;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only access to m18DutyRoster for the HR-management self-service side.
 * Rows are created/maintained by HR elsewhere (hr-administration-backend) — no write
 * methods live here.
 */
@ApplicationScoped
@Slf4j
public class DutyRosterRepository {

    /** A staff member's own ACTIVE roster rows within a date range, earliest first. */
    public Uni<List<DutyRoster>> findByStaffAndDateRange(
            SqlClient client, String staffId, LocalDate from, LocalDate to) {
        return client.preparedQuery("SELECT UniqId, StaffId, StaffName, Department, ProjectCode, ProjectName, "
                        + "ShiftCode, RosterDate, Remarks, Status, EntryStaff, EntryDate, "
                        + "LastEditStaff, LastEditDate "
                        + "FROM m18DutyRoster WHERE StaffId = ? AND RosterDate BETWEEN ? AND ? "
                        + "AND Status = 'ACTIVE' ORDER BY RosterDate")
                .execute(Tuple.tuple().addValue(staffId).addValue(from).addValue(to))
                .map(this::toList);
    }

    private List<DutyRoster> toList(RowSet<Row> rows) {
        List<DutyRoster> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    private DutyRoster toEntity(Row row) {
        DutyRoster e = new DutyRoster();
        e.setUniqId(row.getLong("UniqId"));
        e.setStaffId(row.getString("StaffId"));
        e.setStaffName(row.getString("StaffName"));
        e.setDepartment(row.getString("Department"));
        e.setProjectCode(row.getString("ProjectCode"));
        e.setProjectName(row.getString("ProjectName"));
        e.setShiftCode(row.getString("ShiftCode"));
        e.setRosterDate(row.getLocalDate("RosterDate"));
        e.setRemarks(row.getString("Remarks"));
        e.setStatus(row.getString("Status"));
        e.setEntryStaff(row.getString("EntryStaff"));
        e.setEntryDate(row.getLocalDateTime("EntryDate"));
        e.setLastEditStaff(row.getString("LastEditStaff"));
        e.setLastEditDate(row.getLocalDateTime("LastEditDate"));
        return e;
    }
}
