package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.StaffLeaveEntitlement;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only view of m18StaffLeaveEntitlement so a balance can prefer HR's assigned
 * entitlement over the leave-type ladder. Every method takes the company-routed pool.
 */
@ApplicationScoped
@Slf4j
public class StaffLeaveEntitlementRepository {

    private static final String COLUMNS =
        "StaffId, LeaveType, LeaveYear, EntitledDays, ServiceYears, Source";

    /** The assigned entitlement for one staff/type/year, or null when none. */
    public Uni<StaffLeaveEntitlement> findByStaffTypeYear(SqlClient client, String staffId, String leaveType, int year) {
        return client.preparedQuery("SELECT " + COLUMNS + " FROM m18StaffLeaveEntitlement " +
                "WHERE StaffId = ? AND LeaveType = ? AND LeaveYear = ?")
            .execute(Tuple.of(staffId, leaveType, year))
            .map(rows -> rows.iterator().hasNext() ? toEntity(rows.iterator().next()) : null)
            .onFailure().invoke(e -> log.error("Error finding entitlement for {}/{}/{}: {}",
                    staffId, leaveType, year, e.getMessage(), e));
    }

    /** Every assigned entitlement for one staff in a given year (all leave types). */
    public Uni<List<StaffLeaveEntitlement>> findByStaffYear(SqlClient client, String staffId, int year) {
        return client.preparedQuery("SELECT " + COLUMNS + " FROM m18StaffLeaveEntitlement " +
                "WHERE StaffId = ? AND LeaveYear = ?")
            .execute(Tuple.of(staffId, year))
            .map(this::toList)
            .onFailure().invoke(e -> log.error("Error listing entitlements for {}/{}: {}",
                    staffId, year, e.getMessage(), e));
    }

    private List<StaffLeaveEntitlement> toList(RowSet<Row> rows) {
        List<StaffLeaveEntitlement> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    private StaffLeaveEntitlement toEntity(Row row) {
        StaffLeaveEntitlement e = new StaffLeaveEntitlement();
        e.setStaffId(row.getString("StaffId"));
        e.setLeaveType(row.getString("LeaveType"));
        e.setLeaveYear(row.getInteger("LeaveYear"));
        e.setEntitledDays(row.getBigDecimal("EntitledDays"));
        e.setServiceYears(row.getInteger("ServiceYears"));
        e.setSource(row.getString("Source"));
        return e;
    }
}
