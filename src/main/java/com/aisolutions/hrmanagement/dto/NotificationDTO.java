package com.aisolutions.hrmanagement.dto;

import com.aisolutions.hrmanagement.entity.Notification;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Read model for the notification bell. */
@Data
@NoArgsConstructor
public class NotificationDTO {
    private Long id;
    private String moduleId;
    private String notificationType;
    private String notificationSubject;
    private String notificationDesc;
    private String notifyStaff;
    private String readStatus;
    private String entryStaff;
    private LocalDateTime entryDate;

    public static NotificationDTO fromEntity(Notification n) {
        NotificationDTO d = new NotificationDTO();
        d.setId(n.getUniqId());
        d.setModuleId(n.getModuleId());
        d.setNotificationType(n.getNotificationType());
        d.setNotificationSubject(n.getNotificationSubject());
        d.setNotificationDesc(n.getNotificationDesc());
        d.setNotifyStaff(n.getNotifyStaff());
        d.setReadStatus(n.getReadStatus());
        d.setEntryStaff(n.getEntryStaff());
        d.setEntryDate(n.getEntryDate());
        return d;
    }
}
