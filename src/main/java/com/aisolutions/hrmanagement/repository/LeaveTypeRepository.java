package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.dto.DropdownOptionDTO;
import com.aisolutions.hrmanagement.entity.LeaveType;
import com.aisolutions.hrmanagement.entity.LeaveTypeEntitlement;

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
public class LeaveTypeRepository {

    /** Leave types as dropdown options (value = code, label = description). */
    public Uni<List<DropdownOptionDTO>> findAllOptions(SqlClient client) {
        return client.preparedQuery(
                "SELECT LeaveType, Description FROM m01LeaveType ORDER BY LeaveType")
            .execute()
            .map(rows -> {
                List<DropdownOptionDTO> result = new ArrayList<>();
                for (Row row : rows) {
                    String code = row.getString("LeaveType");
                    String desc = row.getString("Description");
                    result.add(new DropdownOptionDTO(code, desc != null ? desc : code));
                }
                return result;
            });
    }

    /** Description for one leave-type code, or null when unknown. */
    public Uni<String> findDescription(SqlClient client, String leaveType) {
        if (leaveType == null || leaveType.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        return client.preparedQuery("SELECT Description FROM m01LeaveType WHERE LeaveType = ?")
            .execute(Tuple.tuple().addValue(leaveType))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next().getString("Description") : null);
    }

    /** Entitlement bands for a leave type, ascending by YearOfService. */
    public Uni<List<LeaveTypeEntitlement>> findEntitlements(SqlClient client, String leaveType) {
        return client.preparedQuery(
                "SELECT UniqId, LeaveType, YearOfService, DaysOfLeave " +
                "FROM m01LeaveTypeEntitlement WHERE LeaveType = ? ORDER BY YearOfService ASC")
            .execute(Tuple.tuple().addValue(leaveType))
            .map(this::toEntitlementList);
    }

    /** Every entitlement band, ordered by leave type then YearOfService ascending. */
    public Uni<List<LeaveTypeEntitlement>> findAllEntitlements(SqlClient client) {
        return client.preparedQuery(
                "SELECT UniqId, LeaveType, YearOfService, DaysOfLeave " +
                "FROM m01LeaveTypeEntitlement ORDER BY LeaveType ASC, YearOfService ASC")
            .execute()
            .map(this::toEntitlementList);
    }

    private List<LeaveTypeEntitlement> toEntitlementList(RowSet<Row> rows) {
        List<LeaveTypeEntitlement> result = new ArrayList<>();
        for (Row row : rows) {
            LeaveTypeEntitlement e = new LeaveTypeEntitlement();
            e.setUniqId(row.getLong("UniqId"));
            e.setLeaveType(row.getString("LeaveType"));
            e.setYearOfService(row.getInteger("YearOfService"));
            e.setDaysOfLeave(row.getInteger("DaysOfLeave"));
            result.add(e);
        }
        return result;
    }
}
