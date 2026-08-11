package com.aisolutions.hrmanagement.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * On-screen HRMS notification (m07Notifications).
 *
 * One row is one message addressed to a single staff member (NotifyStaff) within
 * a module (ModuleId, "mod18" for HRMS). ReadStatus is "Unread" until the user
 * opens it, then "Read". Rows are written by the claim/approval action hooks.
 */
@Entity
@Table(name = "m07Notifications")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId")
    private Long uniqId;

    @Column(name = "EntryStaff", length = 25)
    private String entryStaff;

    @Column(name = "EntryDate")
    private LocalDateTime entryDate;

    @Column(name = "LastEditStaff", length = 25)
    private String lastEditStaff;

    @Column(name = "LastEditDate")
    private LocalDateTime lastEditDate;

    @Column(name = "ModuleId", length = 25)
    private String moduleId;

    @Column(name = "NotificationType", length = 50)
    private String notificationType;

    @Column(name = "NotificationDesc", length = 255)
    private String notificationDesc;

    @Column(name = "NotifyStaff", length = 25)
    private String notifyStaff;

    @Column(name = "ReadStatus", length = 15)
    private String readStatus;

    // m07Notifications.NotificationSubject is varchar(200) — widened from 50 so the
    // claim-action subject templates fit (a submitter name + claim period overflow 50).
    @Column(name = "NotificationSubject", length = 200)
    private String notificationSubject;

    /**
     * The claim this notification refers to (its header UniqId, as text) — set at creation
     * so the bell can deep-link straight to the claim, with no parsing of the message text.
     */
    @Column(name = "ReferenceNo", length = 45)
    private String referenceNo;
}
