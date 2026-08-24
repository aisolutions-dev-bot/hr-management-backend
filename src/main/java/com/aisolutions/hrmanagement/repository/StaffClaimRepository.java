package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.StaffClaim;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.mysqlclient.MySQLClient;
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
public class StaffClaimRepository {

    public Uni<StaffClaim> findById(SqlClient client, Long id) {
        return client.preparedQuery(
                "SELECT UniqId, StaffId, EntryStaff, EntryDate, LastEditStaff, LastEditDate, " +
                "ClaimPeriod, ClaimAmount, Status, SubmittedDate, ApprovedAmount, RejectedAmount " +
                "FROM m18StaffClaims WHERE UniqId = ?")
            .execute(Tuple.tuple().addValue(id))
            .map(rows -> rows.iterator().hasNext() ? toEntity(rows.iterator().next()) : null);
    }

    public Uni<List<StaffClaim>> findByStaff(SqlClient client, String staffId) {
        return client.preparedQuery(
                "SELECT UniqId, StaffId, EntryStaff, EntryDate, LastEditStaff, LastEditDate, " +
                "ClaimPeriod, ClaimAmount, Status, SubmittedDate, ApprovedAmount, RejectedAmount " +
                "FROM m18StaffClaims WHERE StaffId = ? ORDER BY UniqId DESC")
            .execute(Tuple.tuple().addValue(staffId))
            .map(this::toList);
    }

    /**
     * The staff member's header for a period in a given status, or null.
     */
    public Uni<StaffClaim> findByStaffPeriodStatus(SqlClient client, String staffId, String period, String status) {
        return client.preparedQuery(
                "SELECT UniqId, StaffId, EntryStaff, EntryDate, LastEditStaff, LastEditDate, " +
                "ClaimPeriod, ClaimAmount, Status, SubmittedDate, ApprovedAmount, RejectedAmount " +
                "FROM m18StaffClaims WHERE StaffId = ? AND ClaimPeriod = ? AND Status = ? " +
                "ORDER BY UniqId DESC LIMIT 1")
            .execute(Tuple.tuple().addValue(staffId).addValue(period).addValue(status))
            .map(rows -> rows.iterator().hasNext() ? toEntity(rows.iterator().next()) : null);
    }

    /**
     * The staff member's claim header id for a period, any status, or null.
     */
    public Uni<Long> findIdByStaffAndPeriod(SqlClient client, String staffId, String period) {
        return client.preparedQuery(
                "SELECT UniqId FROM m18StaffClaims WHERE StaffId = ? AND ClaimPeriod = ? " +
                "ORDER BY UniqId DESC LIMIT 1")
            .execute(Tuple.tuple().addValue(staffId).addValue(period))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next().getLong("UniqId") : null);
    }

    /**
     * This staff's numbered periods under a base month.
     */
    public Uni<List<String>> findPeriodsWithSuffix(SqlClient client, String staffId, String basePeriod) {
        return client.preparedQuery(
                "SELECT ClaimPeriod FROM m18StaffClaims WHERE StaffId = ? AND ClaimPeriod LIKE ?")
            .execute(Tuple.tuple().addValue(staffId).addValue(basePeriod + "-%"))
            .map(rows -> {
                List<String> result = new ArrayList<>();
                for (Row row : rows) {
                    result.add(row.getString("ClaimPeriod"));
                }
                return result;
            });
    }

    public Uni<StaffClaim> save(SqlClient client, StaffClaim entity) {
        return client.preparedQuery(
                "INSERT INTO m18StaffClaims (StaffId, EntryStaff, EntryDate, LastEditStaff, LastEditDate, " +
                "ClaimPeriod, ClaimAmount, Status, SubmittedDate, ApprovedAmount, RejectedAmount) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .execute(Tuple.tuple()
                .addValue(entity.getStaffId())
                .addValue(entity.getEntryStaff())
                .addValue(entity.getEntryDate())
                .addValue(entity.getLastEditStaff())
                .addValue(entity.getLastEditDate())
                .addValue(entity.getClaimPeriod())
                .addValue(entity.getClaimAmount())
                .addValue(entity.getStatus())
                .addValue(entity.getSubmittedDate())
                .addValue(entity.getApprovedAmount())
                .addValue(entity.getRejectedAmount()))
            .flatMap(result -> {
                Long id = result.property(MySQLClient.LAST_INSERTED_ID);
                entity.setUniqId(id);
                return Uni.createFrom().item(entity);
            });
    }

    public Uni<StaffClaim> update(SqlClient client, StaffClaim entity) {
        return client.preparedQuery(
                "UPDATE m18StaffClaims SET StaffId = ?, EntryStaff = ?, EntryDate = ?, " +
                "LastEditStaff = ?, LastEditDate = ?, ClaimPeriod = ?, ClaimAmount = ?, " +
                "Status = ?, SubmittedDate = ?, ApprovedAmount = ?, RejectedAmount = ? " +
                "WHERE UniqId = ?")
            .execute(Tuple.tuple()
                .addValue(entity.getStaffId())
                .addValue(entity.getEntryStaff())
                .addValue(entity.getEntryDate())
                .addValue(entity.getLastEditStaff())
                .addValue(entity.getLastEditDate())
                .addValue(entity.getClaimPeriod())
                .addValue(entity.getClaimAmount())
                .addValue(entity.getStatus())
                .addValue(entity.getSubmittedDate())
                .addValue(entity.getApprovedAmount())
                .addValue(entity.getRejectedAmount())
                .addValue(entity.getUniqId()))
            .map(v -> entity);
    }

    private List<StaffClaim> toList(RowSet<Row> rows) {
        List<StaffClaim> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    private StaffClaim toEntity(Row row) {
        StaffClaim e = new StaffClaim();
        e.setUniqId(row.getLong("UniqId"));
        e.setStaffId(row.getString("StaffId"));
        e.setEntryStaff(row.getString("EntryStaff"));
        e.setEntryDate(row.getLocalDateTime("EntryDate"));
        e.setLastEditStaff(row.getString("LastEditStaff"));
        e.setLastEditDate(row.getLocalDateTime("LastEditDate"));
        e.setClaimPeriod(row.getString("ClaimPeriod"));
        e.setClaimAmount(row.getBigDecimal("ClaimAmount"));
        e.setStatus(row.getString("Status"));
        e.setSubmittedDate(row.getLocalDateTime("SubmittedDate"));
        e.setApprovedAmount(row.getBigDecimal("ApprovedAmount"));
        e.setRejectedAmount(row.getBigDecimal("RejectedAmount"));
        return e;
    }
}
