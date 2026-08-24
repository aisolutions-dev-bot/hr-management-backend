package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.Notification;
import com.aisolutions.shared.util.DateUtil;

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
public class NotificationRepository {

    private static final String STAFF_TYPES = "('Staff-Claims', 'Staff-Leaves')";

    /** A staff member's staff-facing notifications for one module, newest first. */
    public Uni<List<Notification>> findForStaff(SqlClient client, String staffId, String moduleId) {
        return client.preparedQuery(
                "SELECT UniqId, EntryStaff, EntryDate, LastEditStaff, LastEditDate, ModuleId, " +
                "NotificationType, NotificationDesc, NotifyStaff, ReadStatus, NotificationSubject, ReferenceNo " +
                "FROM m07Notifications WHERE NotifyStaff = ? AND ModuleId = ? " +
                "AND NotificationType IN " + STAFF_TYPES + " " +
                "ORDER BY EntryDate DESC, UniqId DESC")
            .execute(Tuple.tuple().addValue(staffId).addValue(moduleId))
            .map(this::toList);
    }

    /** Count of unread staff-facing notifications for the bell badge. */
    public Uni<Long> countUnread(SqlClient client, String staffId, String moduleId) {
        return client.preparedQuery(
                "SELECT COUNT(*) AS cnt FROM m07Notifications WHERE NotifyStaff = ? " +
                "AND ModuleId = ? AND NotificationType IN " + STAFF_TYPES + " " +
                "AND (ReadStatus IS NULL OR ReadStatus <> 'Read')")
            .execute(Tuple.tuple().addValue(staffId).addValue(moduleId))
            .map(rows -> rows.iterator().next().getLong("cnt"));
    }

    /**
     * Flip one notification to 'Read'. Scoped to its owner.
     * Returns the updated row, or null if not found / not theirs.
     */
    public Uni<Notification> markRead(SqlClient client, Long uniqId, String staffId) {
        return client.preparedQuery(
                "SELECT UniqId, EntryStaff, EntryDate, LastEditStaff, LastEditDate, ModuleId, " +
                "NotificationType, NotificationDesc, NotifyStaff, ReadStatus, NotificationSubject, ReferenceNo " +
                "FROM m07Notifications WHERE UniqId = ?")
            .execute(Tuple.tuple().addValue(uniqId))
            .flatMap(rows -> {
                if (!rows.iterator().hasNext()) {
                    return Uni.createFrom().nullItem();
                }
                Notification n = toEntity(rows.iterator().next());
                if (staffId != null && !staffId.equalsIgnoreCase(n.getNotifyStaff())) {
                    return Uni.createFrom().nullItem();
                }
                return client.preparedQuery(
                        "UPDATE m07Notifications SET ReadStatus = 'Read', LastEditStaff = ?, LastEditDate = ? " +
                        "WHERE UniqId = ?")
                    .execute(Tuple.tuple().addValue(staffId).addValue(DateUtil.nowSGT()).addValue(uniqId))
                    .map(v -> {
                        n.setReadStatus("Read");
                        n.setLastEditStaff(staffId);
                        n.setLastEditDate(DateUtil.nowSGT());
                        return n;
                    });
            });
    }

    /**
     * Flip every unread notification for this staff member (in one module) to 'Read'.
     * Returns the number of rows updated.
     */
    public Uni<Integer> markAllRead(SqlClient client, String staffId, String moduleId) {
        return client.preparedQuery(
                "UPDATE m07Notifications SET ReadStatus = 'Read', LastEditStaff = ?, LastEditDate = ? " +
                "WHERE LOWER(NotifyStaff) = LOWER(?) AND ModuleId = ? " +
                "AND NotificationType IN " + STAFF_TYPES + " " +
                "AND (ReadStatus IS NULL OR ReadStatus <> 'Read')")
            .execute(Tuple.tuple()
                .addValue(staffId)
                .addValue(DateUtil.nowSGT())
                .addValue(staffId)
                .addValue(moduleId))
            .map(result -> result.rowCount());
    }

    /**
     * Persist a new notification. New rows always start 'Unread'.
     */
    public Uni<Notification> create(SqlClient client, String moduleId, String notificationType, String subject,
                                    String desc, String notifyStaff, String entryStaff,
                                    String referenceNo) {
        return client.preparedQuery(
                "INSERT INTO m07Notifications (ModuleId, NotificationType, NotificationSubject, " +
                "NotificationDesc, NotifyStaff, ReadStatus, EntryStaff, EntryDate, ReferenceNo) " +
                "VALUES (?, ?, ?, ?, ?, 'Unread', ?, ?, ?)")
            .execute(Tuple.tuple()
                .addValue(moduleId)
                .addValue(notificationType)
                .addValue(subject)
                .addValue(desc)
                .addValue(notifyStaff)
                .addValue(entryStaff)
                .addValue(DateUtil.nowSGT())
                .addValue(referenceNo))
            .flatMap(result -> {
                Long id = result.property(MySQLClient.LAST_INSERTED_ID);
                Notification n = new Notification();
                n.setUniqId(id);
                n.setModuleId(moduleId);
                n.setNotificationType(notificationType);
                n.setNotificationSubject(subject);
                n.setNotificationDesc(desc);
                n.setNotifyStaff(notifyStaff);
                n.setReadStatus("Unread");
                n.setEntryStaff(entryStaff);
                n.setEntryDate(DateUtil.nowSGT());
                n.setReferenceNo(referenceNo);
                return Uni.createFrom().item(n);
            });
    }

    /** Find notification by ID. */
    public Uni<Notification> findById(SqlClient client, Long uniqId) {
        return client.preparedQuery(
                "SELECT UniqId, EntryStaff, EntryDate, LastEditStaff, LastEditDate, ModuleId, " +
                "NotificationType, NotificationDesc, NotifyStaff, ReadStatus, NotificationSubject, ReferenceNo " +
                "FROM m07Notifications WHERE UniqId = ?")
            .execute(Tuple.tuple().addValue(uniqId))
            .map(rows -> rows.iterator().hasNext() ? toEntity(rows.iterator().next()) : null);
    }

    private List<Notification> toList(RowSet<Row> rows) {
        List<Notification> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    private Notification toEntity(Row row) {
        Notification n = new Notification();
        n.setUniqId(row.getLong("UniqId"));
        n.setEntryStaff(row.getString("EntryStaff"));
        n.setEntryDate(row.getLocalDateTime("EntryDate"));
        n.setLastEditStaff(row.getString("LastEditStaff"));
        n.setLastEditDate(row.getLocalDateTime("LastEditDate"));
        n.setModuleId(row.getString("ModuleId"));
        n.setNotificationType(row.getString("NotificationType"));
        n.setNotificationDesc(row.getString("NotificationDesc"));
        n.setNotifyStaff(row.getString("NotifyStaff"));
        n.setReadStatus(row.getString("ReadStatus"));
        n.setNotificationSubject(row.getString("NotificationSubject"));
        n.setReferenceNo(row.getString("ReferenceNo"));
        return n;
    }
}
