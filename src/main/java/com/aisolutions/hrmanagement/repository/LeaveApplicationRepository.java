package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.LeaveApplication;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.mysqlclient.MySQLClient;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Slf4j
public class LeaveApplicationRepository {

    public Uni<LeaveApplication> findById(SqlClient client, Long id) {
        return client.preparedQuery(
                "SELECT UniqId, StaffId, StaffName, Department, ApplicationDate, LeaveAction, " +
                "LeaveType, Remarks, FromDate, ToDate, HalfDayPeriod, TotalDays, CancelRefId, " +
                "ApproverStaffId, Status, ApprovedBy, ApprovedDate, RejectReason, " +
                "EntryStaff, EntryDate, LastEditStaff, LastEditDate " +
                "FROM m18LeaveApplications WHERE UniqId = ?")
            .execute(Tuple.tuple().addValue(id))
            .map(rows -> rows.iterator().hasNext() ? toEntity(rows.iterator().next()) : null);
    }

    public Uni<LeaveApplication> save(SqlClient client, LeaveApplication entity) {
        return client.preparedQuery(
                "INSERT INTO m18LeaveApplications (StaffId, StaffName, Department, ApplicationDate, " +
                "LeaveAction, LeaveType, Remarks, FromDate, ToDate, HalfDayPeriod, TotalDays, " +
                "CancelRefId, ApproverStaffId, Status, ApprovedBy, ApprovedDate, RejectReason, " +
                "EntryStaff, EntryDate, LastEditStaff, LastEditDate) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .execute(Tuple.tuple()
                .addValue(entity.getStaffId())
                .addValue(entity.getStaffName())
                .addValue(entity.getDepartment())
                .addValue(entity.getApplicationDate())
                .addValue(entity.getLeaveAction())
                .addValue(entity.getLeaveType())
                .addValue(entity.getRemarks())
                .addValue(entity.getFromDate())
                .addValue(entity.getToDate())
                .addValue(entity.getHalfDayPeriod())
                .addValue(entity.getTotalDays())
                .addValue(entity.getCancelRefId())
                .addValue(entity.getApproverStaffId())
                .addValue(entity.getStatus())
                .addValue(entity.getApprovedBy())
                .addValue(entity.getApprovedDate())
                .addValue(entity.getRejectReason())
                .addValue(entity.getEntryStaff())
                .addValue(entity.getEntryDate())
                .addValue(entity.getLastEditStaff())
                .addValue(entity.getLastEditDate()))
            .flatMap(result -> {
                Long id = result.property(MySQLClient.LAST_INSERTED_ID);
                entity.setUniqId(id);
                return Uni.createFrom().item(entity);
            });
    }

    public Uni<LeaveApplication> update(SqlClient client, LeaveApplication entity) {
        return client.preparedQuery(
                "UPDATE m18LeaveApplications SET StaffId = ?, StaffName = ?, Department = ?, " +
                "ApplicationDate = ?, LeaveAction = ?, LeaveType = ?, Remarks = ?, FromDate = ?, " +
                "ToDate = ?, HalfDayPeriod = ?, TotalDays = ?, CancelRefId = ?, ApproverStaffId = ?, " +
                "Status = ?, ApprovedBy = ?, ApprovedDate = ?, RejectReason = ?, " +
                "EntryStaff = ?, EntryDate = ?, LastEditStaff = ?, LastEditDate = ? " +
                "WHERE UniqId = ?")
            .execute(Tuple.tuple()
                .addValue(entity.getStaffId())
                .addValue(entity.getStaffName())
                .addValue(entity.getDepartment())
                .addValue(entity.getApplicationDate())
                .addValue(entity.getLeaveAction())
                .addValue(entity.getLeaveType())
                .addValue(entity.getRemarks())
                .addValue(entity.getFromDate())
                .addValue(entity.getToDate())
                .addValue(entity.getHalfDayPeriod())
                .addValue(entity.getTotalDays())
                .addValue(entity.getCancelRefId())
                .addValue(entity.getApproverStaffId())
                .addValue(entity.getStatus())
                .addValue(entity.getApprovedBy())
                .addValue(entity.getApprovedDate())
                .addValue(entity.getRejectReason())
                .addValue(entity.getEntryStaff())
                .addValue(entity.getEntryDate())
                .addValue(entity.getLastEditStaff())
                .addValue(entity.getLastEditDate())
                .addValue(entity.getUniqId()))
            .map(v -> entity);
    }

    /** A staff member's leave applications, newest first. */
    public Uni<List<LeaveApplication>> findByStaff(SqlClient client, String staffId) {
        return client.preparedQuery(
                "SELECT UniqId, StaffId, StaffName, Department, ApplicationDate, LeaveAction, " +
                "LeaveType, Remarks, FromDate, ToDate, HalfDayPeriod, TotalDays, CancelRefId, " +
                "ApproverStaffId, Status, ApprovedBy, ApprovedDate, RejectReason, " +
                "EntryStaff, EntryDate, LastEditStaff, LastEditDate " +
                "FROM m18LeaveApplications WHERE StaffId = ? ORDER BY UniqId DESC")
            .execute(Tuple.tuple().addValue(staffId))
            .map(this::toList);
    }

    /** Cancelable leaves for a staff member (APPLY + APPROVED). */
    public Uni<List<LeaveApplication>> findCancelable(SqlClient client, String staffId, String leaveType) {
        return client.preparedQuery(
                "SELECT UniqId, StaffId, StaffName, Department, ApplicationDate, LeaveAction, " +
                "LeaveType, Remarks, FromDate, ToDate, HalfDayPeriod, TotalDays, CancelRefId, " +
                "ApproverStaffId, Status, ApprovedBy, ApprovedDate, RejectReason, " +
                "EntryStaff, EntryDate, LastEditStaff, LastEditDate " +
                "FROM m18LeaveApplications WHERE StaffId = ? AND LeaveType = ? " +
                "AND LeaveAction = 'APPLY' AND Status = 'APPROVED' " +
                "ORDER BY FromDate DESC, UniqId DESC")
            .execute(Tuple.tuple().addValue(staffId).addValue(leaveType))
            .map(this::toList);
    }

    /** Still-pending leave days for a leave type in a date window (a soft hold, not yet committed). */
    public Uni<BigDecimal> sumPendingDays(SqlClient client, String staffId, String leaveType,
                                          LocalDate windowStart, LocalDate windowEnd) {
        return client.preparedQuery(
                "SELECT SUM(TotalDays) AS total FROM m18LeaveApplications " +
                "WHERE StaffId = ? AND LeaveType = ? " +
                "AND LeaveAction = 'APPLY' AND Status = 'PENDING' " +
                "AND FromDate >= ? AND FromDate <= ?")
            .execute(Tuple.tuple().addValue(staffId).addValue(leaveType)
                .addValue(windowStart).addValue(windowEnd))
            .map(rows -> {
                if (!rows.iterator().hasNext()) return BigDecimal.ZERO;
                BigDecimal sum = rows.iterator().next().getBigDecimal("total");
                return sum != null ? sum : BigDecimal.ZERO;
            });
    }

    /** Booked days grouped by leave type and status. */
    public Uni<List<Row>> sumBookedDaysByTypeAndStatus(SqlClient client, String staffId,
                                                       LocalDate windowStart, LocalDate windowEnd) {
        return client.preparedQuery(
                "SELECT LeaveType, Status, SUM(TotalDays) AS total FROM m18LeaveApplications " +
                "WHERE StaffId = ? " +
                "AND LeaveAction = 'APPLY' AND Status IN ('PENDING', 'APPROVED') " +
                "AND FromDate >= ? AND FromDate <= ? " +
                "GROUP BY LeaveType, Status")
            .execute(Tuple.tuple().addValue(staffId).addValue(windowStart).addValue(windowEnd))
            .map(rows -> {
                List<Row> result = new ArrayList<>();
                for (Row row : rows) {
                    result.add(row);
                }
                return result;
            });
    }

    private List<LeaveApplication> toList(RowSet<Row> rows) {
        List<LeaveApplication> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    private LeaveApplication toEntity(Row row) {
        LeaveApplication e = new LeaveApplication();
        e.setUniqId(row.getLong("UniqId"));
        e.setStaffId(row.getString("StaffId"));
        e.setStaffName(row.getString("StaffName"));
        e.setDepartment(row.getString("Department"));
        e.setApplicationDate(row.getLocalDateTime("ApplicationDate"));
        e.setLeaveAction(row.getString("LeaveAction"));
        e.setLeaveType(row.getString("LeaveType"));
        e.setRemarks(row.getString("Remarks"));
        e.setFromDate(row.getLocalDate("FromDate"));
        e.setToDate(row.getLocalDate("ToDate"));
        e.setHalfDayPeriod(row.getString("HalfDayPeriod"));
        e.setTotalDays(row.getBigDecimal("TotalDays"));
        e.setCancelRefId(row.getLong("CancelRefId"));
        e.setApproverStaffId(row.getString("ApproverStaffId"));
        e.setStatus(row.getString("Status"));
        e.setApprovedBy(row.getString("ApprovedBy"));
        e.setApprovedDate(row.getLocalDateTime("ApprovedDate"));
        e.setRejectReason(row.getString("RejectReason"));
        e.setEntryStaff(row.getString("EntryStaff"));
        e.setEntryDate(row.getLocalDateTime("EntryDate"));
        e.setLastEditStaff(row.getString("LastEditStaff"));
        e.setLastEditDate(row.getLocalDateTime("LastEditDate"));
        return e;
    }
}
