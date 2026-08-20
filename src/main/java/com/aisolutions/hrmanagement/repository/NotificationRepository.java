package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.entity.Notification;

import com.aisolutions.shared.util.DateUtil;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
@WithSession
public class NotificationRepository implements PanacheRepositoryBase<Notification, Long> {

    // Staff-facing notification types shown in the HRMS bell: claim outcomes + leave
    // (both the applicant's outcome and the approver's "please review", since both parties
    // are staff using this portal).
    private static final String STAFF_TYPES = "('Staff-Claims', 'Staff-Leaves')";

    /** A staff member's staff-facing notifications for one module, newest first. */
    public Uni<List<Notification>> findForStaff(String staffId, String moduleId) {
        return getSession().flatMap(session ->
            session.createQuery(
                "FROM Notification WHERE notifyStaff = :staffId AND moduleId = :moduleId "
                    + "AND notificationType IN " + STAFF_TYPES + " "
                    + "ORDER BY entryDate DESC, uniqId DESC",
                Notification.class)
                .setParameter("staffId", staffId)
                .setParameter("moduleId", moduleId)
                .getResultList());
    }

    /** Count of unread staff-facing notifications (anything not yet 'Read') for the bell badge. */
    public Uni<Long> countUnread(String staffId, String moduleId) {
        return getSession().flatMap(session ->
            session.createQuery(
                "SELECT COUNT(n) FROM Notification n WHERE n.notifyStaff = :staffId "
                    + "AND n.moduleId = :moduleId AND n.notificationType IN " + STAFF_TYPES + " "
                    + "AND (n.readStatus IS NULL OR n.readStatus <> 'Read')",
                Long.class)
                .setParameter("staffId", staffId)
                .setParameter("moduleId", moduleId)
                .getSingleResult());
    }

    /**
     * Flip one notification to 'Read'. Scoped to its owner so a staff member can
     * only read their own; returns the updated row, or null if not found / not theirs.
     */
    public Uni<Notification> markRead(Long uniqId, String staffId) {
        // Must run in a TRANSACTION, not just @WithSession: a session on its own opens no
        // transaction, so the flip to 'Read' was flushed nowhere and silently discarded —
        // the row stayed Unread. Panache.withTransaction commits the change.
        return Panache.withTransaction(() ->
            getSession().flatMap(session ->
                session.find(Notification.class, uniqId).flatMap(n -> {
                    // Case-insensitive ownership check: the list/count queries match
                    // notifyStaff case-insensitively (MySQL collation), so a login id that
                    // differs only in case (e.g. "superdrew" vs stored "SUPERDREW") still
                    // sees the row — the read must recognise it as theirs too, or it would
                    // 404 and never flip to Read.
                    if (n == null || (staffId != null && !staffId.equalsIgnoreCase(n.getNotifyStaff()))) {
                        return Uni.createFrom().nullItem();
                    }
                    n.setReadStatus("Read");
                    n.setLastEditStaff(staffId);
                    n.setLastEditDate(DateUtil.nowSGT());
                    return session.merge(n);
                })));
    }

    /**
     * Flip every unread notification for this staff member (in one module) to 'Read'.
     * Runs in a transaction (a bulk update must commit) and matches notifyStaff
     * case-insensitively, like the list/markRead. Returns the number of rows updated.
     */
    public Uni<Integer> markAllRead(String staffId, String moduleId) {
        return Panache.withTransaction(() ->
            getSession().flatMap(session ->
                session.createQuery(
                    "UPDATE Notification SET readStatus = 'Read', lastEditStaff = :staff, "
                        + "lastEditDate = :now WHERE LOWER(notifyStaff) = LOWER(:staff) "
                        + "AND moduleId = :moduleId AND notificationType IN " + STAFF_TYPES + " "
                        + "AND (readStatus IS NULL OR readStatus <> 'Read')")
                    .setParameter("staff", staffId)
                    .setParameter("moduleId", moduleId)
                    .setParameter("now", DateUtil.nowSGT())
                    .executeUpdate()));
    }

    /**
     * Persist a new notification (used by the action hooks). New rows
     * always start 'Unread'. EntryDate is SGT, consistent with the rest of HRMS.
     */
    public Uni<Notification> create(String moduleId, String notificationType, String subject,
                                    String desc, String notifyStaff, String entryStaff,
                                    String referenceNo) {
        return getSession().flatMap(session -> {
            Notification n = new Notification();
            n.setModuleId(moduleId);
            n.setNotificationType(notificationType);
            n.setNotificationSubject(subject);
            n.setNotificationDesc(desc);
            n.setNotifyStaff(notifyStaff);
            n.setReadStatus("Unread");
            n.setEntryStaff(entryStaff);
            n.setEntryDate(DateUtil.nowSGT());
            n.setReferenceNo(referenceNo);
            return session.persist(n).replaceWith(n);
        });
    }
}
