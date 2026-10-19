package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.StaffClaimDetail;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.mysqlclient.MySQLClient;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Slf4j
public class StaffClaimDetailRepository {

    public Uni<StaffClaimDetail> findById(SqlClient client, Long id) {
        return client.preparedQuery(
                "SELECT UniqId, EntryStaff, EntryDate, LastEditStaff, LastEditDate, ClaimId, " +
                "StaffId, ProjectId, ClaimType, ClaimDate, ClaimDescription, MerchantName, " +
                "ReceiptNumber, ReceiptDate, ReceiptAmount, ClaimAmount, Currency, DetectedCurrency, " +
                "ExchangeRate, Status, ApprovedBy, ApprovedDate, RejectReason " +
                "FROM m18StaffClaimsDet WHERE UniqId = ?")
            .execute(Tuple.tuple().addValue(id))
            .map(rows -> rows.iterator().hasNext() ? toEntity(rows.iterator().next()) : null);
    }

    public Uni<List<StaffClaimDetail>> findByStaffAndDateRange(
            SqlClient client, String staffId, LocalDateTime from, LocalDateTime to) {
        return client.preparedQuery(
                "SELECT UniqId, EntryStaff, EntryDate, LastEditStaff, LastEditDate, ClaimId, " +
                "StaffId, ProjectId, ClaimType, ClaimDate, ClaimDescription, MerchantName, " +
                "ReceiptNumber, ReceiptDate, ReceiptAmount, ClaimAmount, Currency, DetectedCurrency, " +
                "ExchangeRate, Status, ApprovedBy, ApprovedDate, RejectReason " +
                "FROM m18StaffClaimsDet WHERE StaffId = ? AND ClaimDate >= ? AND ClaimDate < ? " +
                "ORDER BY ClaimDate DESC")
            .execute(Tuple.tuple().addValue(staffId).addValue(from).addValue(to))
            .map(this::toList);
    }

    public Uni<List<StaffClaimDetail>> findByStaff(SqlClient client, String staffId) {
        return client.preparedQuery(
                "SELECT UniqId, EntryStaff, EntryDate, LastEditStaff, LastEditDate, ClaimId, " +
                "StaffId, ProjectId, ClaimType, ClaimDate, ClaimDescription, MerchantName, " +
                "ReceiptNumber, ReceiptDate, ReceiptAmount, ClaimAmount, Currency, DetectedCurrency, " +
                "ExchangeRate, Status, ApprovedBy, ApprovedDate, RejectReason " +
                "FROM m18StaffClaimsDet WHERE StaffId = ? ORDER BY ClaimDate DESC")
            .execute(Tuple.tuple().addValue(staffId))
            .map(this::toList);
    }

    /** Line counts grouped by header, for a set of header ids. Returns Row objects with [ClaimId, cnt]. */
    public Uni<List<Row>> countByHeaderIds(SqlClient client, List<Long> headerIds) {
        if (headerIds == null || headerIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        String placeholders = String.join(", ", headerIds.stream().map(s -> "?").toList());
        return client.preparedQuery(
                "SELECT ClaimId, COUNT(UniqId) AS cnt FROM m18StaffClaimsDet " +
                "WHERE ClaimId IN (" + placeholders + ") GROUP BY ClaimId")
            .execute(tupleOf(new ArrayList<>(headerIds)))
            .map(rows -> {
                List<Row> result = new ArrayList<>();
                for (Row row : rows) {
                    result.add(row);
                }
                return result;
            });
    }

    /** All line items belonging to a claim header. */
    public Uni<List<StaffClaimDetail>> findByHeaderId(SqlClient client, Long claimId) {
        return client.preparedQuery(
                "SELECT UniqId, EntryStaff, EntryDate, LastEditStaff, LastEditDate, ClaimId, " +
                "StaffId, ProjectId, ClaimType, ClaimDate, ClaimDescription, MerchantName, " +
                "ReceiptNumber, ReceiptDate, ReceiptAmount, ClaimAmount, Currency, DetectedCurrency, " +
                "ExchangeRate, Status, ApprovedBy, ApprovedDate, RejectReason " +
                "FROM m18StaffClaimsDet WHERE ClaimId = ? ORDER BY ClaimDate ASC, UniqId ASC")
            .execute(Tuple.tuple().addValue(claimId))
            .map(this::toList);
    }

    /**
     * The claim id owning the staff member's receipt with this number, or null.
     */
    public Uni<Long> findClaimIdByStaffAndReceiptNumber(SqlClient client, String staffId, String receiptNumber) {
        return client.preparedQuery(
                "SELECT ClaimId FROM m18StaffClaimsDet WHERE StaffId = ? AND ReceiptNumber = ? " +
                "ORDER BY UniqId DESC LIMIT 1")
            .execute(Tuple.tuple().addValue(staffId).addValue(receiptNumber))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next().getLong("ClaimId") : null);
    }

    public Uni<StaffClaimDetail> save(SqlClient client, StaffClaimDetail entity) {
        return client.preparedQuery(
                "INSERT INTO m18StaffClaimsDet (EntryStaff, EntryDate, LastEditStaff, LastEditDate, " +
                "ClaimId, StaffId, ProjectId, ClaimType, ClaimDate, ClaimDescription, MerchantName, " +
                "ReceiptNumber, ReceiptDate, ReceiptAmount, ClaimAmount, Currency, DetectedCurrency, " +
                "ExchangeRate, Status, ApprovedBy, ApprovedDate, RejectReason) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .execute(Tuple.tuple()
                .addValue(entity.getEntryStaff())
                .addValue(entity.getEntryDate())
                .addValue(entity.getLastEditStaff())
                .addValue(entity.getLastEditDate())
                .addValue(entity.getClaimId())
                .addValue(entity.getStaffId())
                .addValue(entity.getProjectId())
                .addValue(entity.getClaimType())
                .addValue(entity.getClaimDate())
                .addValue(entity.getClaimDescription())
                .addValue(entity.getMerchantName())
                .addValue(entity.getReceiptNumber())
                .addValue(entity.getReceiptDate())
                .addValue(entity.getReceiptAmount())
                .addValue(entity.getClaimAmount())
                .addValue(entity.getCurrency())
                .addValue(entity.getDetectedCurrency())
                .addValue(entity.getExchangeRate())
                .addValue(entity.getStatus())
                .addValue(entity.getApprovedBy())
                .addValue(entity.getApprovedDate())
                .addValue(entity.getRejectReason()))
            .flatMap(result -> {
                Long id = result.property(MySQLClient.LAST_INSERTED_ID);
                entity.setUniqId(id);
                return Uni.createFrom().item(entity);
            });
    }

    public Uni<StaffClaimDetail> update(SqlClient client, StaffClaimDetail entity) {
        return client.preparedQuery(
                "UPDATE m18StaffClaimsDet SET EntryStaff = ?, EntryDate = ?, LastEditStaff = ?, " +
                "LastEditDate = ?, ClaimId = ?, StaffId = ?, ProjectId = ?, ClaimType = ?, " +
                "ClaimDate = ?, ClaimDescription = ?, MerchantName = ?, ReceiptNumber = ?, " +
                "ReceiptDate = ?, ReceiptAmount = ?, ClaimAmount = ?, Currency = ?, " +
                "DetectedCurrency = ?, ExchangeRate = ?, Status = ?, ApprovedBy = ?, " +
                "ApprovedDate = ?, RejectReason = ? WHERE UniqId = ?")
            .execute(Tuple.tuple()
                .addValue(entity.getEntryStaff())
                .addValue(entity.getEntryDate())
                .addValue(entity.getLastEditStaff())
                .addValue(entity.getLastEditDate())
                .addValue(entity.getClaimId())
                .addValue(entity.getStaffId())
                .addValue(entity.getProjectId())
                .addValue(entity.getClaimType())
                .addValue(entity.getClaimDate())
                .addValue(entity.getClaimDescription())
                .addValue(entity.getMerchantName())
                .addValue(entity.getReceiptNumber())
                .addValue(entity.getReceiptDate())
                .addValue(entity.getReceiptAmount())
                .addValue(entity.getClaimAmount())
                .addValue(entity.getCurrency())
                .addValue(entity.getDetectedCurrency())
                .addValue(entity.getExchangeRate())
                .addValue(entity.getStatus())
                .addValue(entity.getApprovedBy())
                .addValue(entity.getApprovedDate())
                .addValue(entity.getRejectReason())
                .addValue(entity.getUniqId()))
            .map(v -> entity);
    }

    public Uni<Boolean> deleteById(SqlClient client, Long id) {
        return client.preparedQuery("DELETE FROM m18StaffClaimsDet WHERE UniqId = ?")
            .execute(Tuple.tuple().addValue(id))
            .map(result -> result.rowCount() > 0);
    }

    private List<StaffClaimDetail> toList(RowSet<Row> rows) {
        List<StaffClaimDetail> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    private StaffClaimDetail toEntity(Row row) {
        StaffClaimDetail e = new StaffClaimDetail();
        e.setUniqId(row.getLong("UniqId"));
        e.setEntryStaff(row.getString("EntryStaff"));
        e.setEntryDate(row.getLocalDateTime("EntryDate"));
        e.setLastEditStaff(row.getString("LastEditStaff"));
        e.setLastEditDate(row.getLocalDateTime("LastEditDate"));
        e.setClaimId(row.getLong("ClaimId"));
        e.setStaffId(row.getString("StaffId"));
        e.setProjectId(row.getString("ProjectId"));
        e.setClaimType(row.getString("ClaimType"));
        e.setClaimDate(row.getLocalDateTime("ClaimDate"));
        e.setClaimDescription(row.getString("ClaimDescription"));
        e.setMerchantName(row.getString("MerchantName"));
        e.setReceiptNumber(row.getString("ReceiptNumber"));
        e.setReceiptDate(row.getLocalDateTime("ReceiptDate"));
        e.setReceiptAmount(row.getBigDecimal("ReceiptAmount"));
        e.setClaimAmount(row.getBigDecimal("ClaimAmount"));
        e.setCurrency(row.getString("Currency"));
        e.setDetectedCurrency(row.getString("DetectedCurrency"));
        e.setExchangeRate(row.getBigDecimal("ExchangeRate"));
        e.setStatus(row.getString("Status"));
        e.setApprovedBy(row.getString("ApprovedBy"));
        e.setApprovedDate(row.getLocalDateTime("ApprovedDate"));
        e.setRejectReason(row.getString("RejectReason"));
        return e;
    }

    private Tuple tupleOf(List<Object> values) {
        Tuple tuple = Tuple.tuple();
        for (Object value : values) {
            tuple.addValue(value);
        }
        return tuple;
    }
}
