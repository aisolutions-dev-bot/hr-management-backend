package com.aisolutions.hrmanagement.repository;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only view of the approval-flow tables (m07Approval* family) for the HRMS side:
 * header, ON tiers, and action log. The HRMS only reads (to render a leave's trail and
 * route the submit notification); decisions and log writes happen on the HR Admin side.
 * Every method takes a {@link SqlClient} (the caller passes the company-routed pool).
 */
@ApplicationScoped
@Slf4j
public class ApprovalFlowRepository {

    /** Flow header: one row per module + screen. */
    public record Header(long refId, String moduleId, String screenType, String mode, String status) {}

    /** One ordered tier and its single approver. */
    public record Tier(int level, String staffId, String staffName, String dept, String status) {}

    /** One logged decision (APPROVE/REJECT). {@code level} may be null for a bare fallback row. */
    public record Action(Integer level, String action, String reason,
                         String actionStaff, String actionStaffName, String actionDept,
                         LocalDateTime actionDate) {}

    /** A document's frozen approval snapshot: its mode + ON tiers as captured at submit. */
    public record Snapshot(String mode, List<Tier> tiers) {}

    /** The flow header for a module + screen, or null when none is configured. */
    public Uni<Header> findHeader(SqlClient client, String moduleId, String screenType) {
        return client.preparedQuery(
                "SELECT UniqId, ModuleId, ScreenType, ApprovalMode, Status FROM m07ApprovalManagement " +
                "WHERE ModuleId = ? AND ScreenType = ? LIMIT 1")
            .execute(Tuple.of(moduleId, screenType))
            .map(rows -> rows.iterator().hasNext() ? toHeader(rows.iterator().next()) : null)
            .onFailure().recoverWithItem((Header) null);
    }

    /** The ON tiers for a header, ordered by level (OFF tiers are skipped = compressed out). */
    public Uni<List<Tier>> findOnTiers(SqlClient client, long referenceId) {
        return client.preparedQuery(
                "SELECT d.ApprovalLevel, d.StaffId, d.Status, s.Name AS StaffName, s.Department AS StaffDept " +
                "FROM m07ApprovalManagementDet d LEFT JOIN m03Staff s ON s.StaffId = d.StaffId " +
                "WHERE d.ReferenceID = ? AND d.Status = 'ON' ORDER BY d.ApprovalLevel")
            .execute(Tuple.of(referenceId))
            .map(this::toTiers)
            .onFailure().recoverWithItem(List.of());
    }

    /** The action log for one document, oldest first. */
    public Uni<List<Action>> findActions(SqlClient client, String moduleId, String screenType, long referenceId) {
        return client.preparedQuery(
                "SELECT a.ApprovalLevel, a.Action, a.Reason, a.ActionStaff, a.ActionDate, " +
                "s.Name AS ActionStaffName, s.Department AS ActionStaffDept " +
                "FROM m07ApprovalAction a LEFT JOIN m03Staff s ON s.StaffId = a.ActionStaff " +
                "WHERE a.ModuleId = ? AND a.ScreenType = ? AND a.ReferenceID = ? " +
                "ORDER BY a.ActionDate, a.UniqId")
            .execute(Tuple.of(moduleId, screenType, referenceId))
            .map(this::toActions)
            .onFailure().recoverWithItem(List.of());
    }

    /** The frozen snapshot for a document (empty tiers = none — the document isn't flow-governed). */
    public Uni<Snapshot> findSnapshot(SqlClient client, String moduleId, String screenType, long referenceId) {
        return client.preparedQuery(
                "SELECT i.ApprovalMode, i.ApprovalLevel, i.StaffId, s.Name AS StaffName, s.Department AS StaffDept " +
                "FROM m07ApprovalInstance i LEFT JOIN m03Staff s ON s.StaffId = i.StaffId " +
                "WHERE i.ModuleId = ? AND i.ScreenType = ? AND i.ReferenceID = ? ORDER BY i.ApprovalLevel")
            .execute(Tuple.of(moduleId, screenType, referenceId))
            .map(this::toSnapshot)
            .onFailure().recoverWithItem(new Snapshot(null, List.of()));
    }

    /** Writes the snapshot rows (one per tier) for a document at submit. Runs on the tx connection. */
    public Uni<Void> insertSnapshot(SqlClient client, String moduleId, String screenType, long referenceId,
                                    String mode, List<Tier> tiers, String actor, LocalDateTime now) {
        if (tiers == null || tiers.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (Tier t : tiers) {
            chain = chain.flatMap(v -> client.preparedQuery(
                    "INSERT INTO m07ApprovalInstance " +
                    "(ModuleId, ScreenType, ReferenceID, ApprovalMode, ApprovalLevel, StaffId, EntryStaff, EntryDate) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .execute(Tuple.tuple()
                    .addValue(moduleId).addValue(screenType).addValue(referenceId)
                    .addValue(mode).addValue(t.level()).addValue(t.staffId())
                    .addValue(actor).addValue(now))
                .replaceWithVoid());
        }
        return chain.onFailure().invoke(e ->
                log.error("Error writing approval snapshot for {} {}/{}: {}",
                        referenceId, moduleId, screenType, e.getMessage(), e));
    }

    private Snapshot toSnapshot(RowSet<Row> rows) {
        String mode = null;
        List<Tier> tiers = new ArrayList<>();
        for (Row row : rows) {
            if (mode == null) mode = row.getString("ApprovalMode");
            tiers.add(new Tier(row.getInteger("ApprovalLevel"), row.getString("StaffId"),
                    row.getString("StaffName"), row.getString("StaffDept"), "ON"));
        }
        return new Snapshot(mode, tiers);
    }

    private Header toHeader(Row row) {
        Integer id = row.getInteger("UniqId");
        return new Header(id != null ? id.longValue() : 0L,
                row.getString("ModuleId"), row.getString("ScreenType"),
                row.getString("ApprovalMode"), row.getString("Status"));
    }

    private List<Tier> toTiers(RowSet<Row> rows) {
        List<Tier> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(new Tier(row.getInteger("ApprovalLevel"), row.getString("StaffId"),
                    row.getString("StaffName"), row.getString("StaffDept"), row.getString("Status")));
        }
        return result;
    }

    private List<Action> toActions(RowSet<Row> rows) {
        List<Action> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(new Action(row.getInteger("ApprovalLevel"), row.getString("Action"),
                    row.getString("Reason"), row.getString("ActionStaff"),
                    row.getString("ActionStaffName"), row.getString("ActionStaffDept"),
                    row.getLocalDateTime("ActionDate")));
        }
        return result;
    }
}
