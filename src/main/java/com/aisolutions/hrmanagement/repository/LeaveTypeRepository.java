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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@Slf4j
public class LeaveTypeRepository {

    /** Codes of leave types granted on request (EligibleOnRequest = 1). Fail-safe empty. */
    public Uni<Set<String>> findEligibleOnRequestCodes(SqlClient client) {
        return client.preparedQuery("SELECT LeaveType FROM m01LeaveType WHERE EligibleOnRequest = 1")
            .execute()
            .map(rows -> {
                Set<String> codes = new HashSet<>();
                for (Row row : rows) {
                    String code = row.getString("LeaveType");
                    if (code != null) codes.add(code);
                }
                return codes;
            })
            .onFailure().recoverWithItem(Set.of());
    }

    /** True when the leave type is granted on request (not auto-entitled from the ladder). Fail-safe false. */
    public Uni<Boolean> isEligibleOnRequest(SqlClient client, String leaveType) {
        if (leaveType == null || leaveType.isBlank()) {
            return Uni.createFrom().item(false);
        }
        return client.preparedQuery("SELECT EligibleOnRequest FROM m01LeaveType WHERE LeaveType = ?")
            .execute(Tuple.of(leaveType.trim()))
            .map(rows -> rows.iterator().hasNext()
                    && Boolean.TRUE.equals(rows.iterator().next().getBoolean("EligibleOnRequest")))
            .onFailure().recoverWithItem(false);
    }

    /** Carry-forward cap (max days that may carry) per leave type; only types with a positive
     *  cap appear. NULL/0 means no cap. Fail-safe empty. */
    public Uni<Map<String, Integer>> findCarryForwardCaps(SqlClient client) {
        return client.preparedQuery(
                "SELECT LeaveType, BroughtForwardCap FROM m01LeaveType " +
                "WHERE BroughtForwardCap IS NOT NULL AND BroughtForwardCap > 0")
            .execute()
            .map(rows -> {
                Map<String, Integer> caps = new HashMap<>();
                for (Row row : rows) {
                    String code = row.getString("LeaveType");
                    Integer cap = row.getInteger("BroughtForwardCap");
                    if (code != null && cap != null) caps.put(code, cap);
                }
                return caps;
            })
            .onFailure().recoverWithItem(Map.of());
    }

    /** Carry-forward cap for one leave type, or null when unset/zero. Fail-safe null. */
    public Uni<Integer> findCarryForwardCap(SqlClient client, String leaveType) {
        if (leaveType == null || leaveType.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        return client.preparedQuery(
                "SELECT BroughtForwardCap FROM m01LeaveType WHERE LeaveType = ?")
            .execute(Tuple.of(leaveType.trim()))
            .map(rows -> {
                if (!rows.iterator().hasNext()) return null;
                Integer cap = rows.iterator().next().getInteger("BroughtForwardCap");
                return (cap != null && cap > 0) ? cap : null;
            })
            .onFailure().recoverWithItem((Integer) null);
    }

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
