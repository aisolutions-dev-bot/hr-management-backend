package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.dto.LeaveLedgerRow;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only view of m18StaffLeaveLedger. Returns the raw ledger rows for a staff member;
 * the balance calculator turns them into buckets + consumption and runs the FIFO / expiry
 * simulation. Every method takes the company-routed pool.
 */
@ApplicationScoped
@Slf4j
public class LeaveLedgerRepository {

    private static final String COLUMNS =
        "LeaveType, TxnType, OriginYear, TxnDate, Days, ExpiryDate, ServiceYears, SourceRefId";

    /** Every ledger row for one staff member and leave type. */
    public Uni<List<LeaveLedgerRow>> findRows(SqlClient client, String staffId, String leaveType) {
        return client.preparedQuery("SELECT " + COLUMNS + " FROM m18StaffLeaveLedger " +
                "WHERE StaffId = ? AND LeaveType = ? ORDER BY TxnDate, UniqId")
            .execute(Tuple.of(staffId, leaveType))
            .map(this::toList)
            .onFailure().invoke(e -> log.error("Error reading ledger for {}/{}: {}",
                    staffId, leaveType, e.getMessage(), e));
    }

    /** Every ledger row for one staff member, all leave types. */
    public Uni<List<LeaveLedgerRow>> findRowsByStaff(SqlClient client, String staffId) {
        return client.preparedQuery("SELECT " + COLUMNS + " FROM m18StaffLeaveLedger " +
                "WHERE StaffId = ? ORDER BY LeaveType, TxnDate, UniqId")
            .execute(Tuple.of(staffId))
            .map(this::toList)
            .onFailure().invoke(e -> log.error("Error reading ledger for {}: {}",
                    staffId, e.getMessage(), e));
    }

    private List<LeaveLedgerRow> toList(io.vertx.mutiny.sqlclient.RowSet<Row> rows) {
        List<LeaveLedgerRow> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(new LeaveLedgerRow(
                    r.getString("LeaveType"),
                    r.getString("TxnType"),
                    r.getInteger("OriginYear") != null ? r.getInteger("OriginYear") : 0,
                    r.getLocalDate("TxnDate"),
                    r.getBigDecimal("Days"),
                    r.getLocalDate("ExpiryDate"),
                    r.getInteger("ServiceYears"),
                    r.getString("SourceRefId")));
        }
        return out;
    }
}
