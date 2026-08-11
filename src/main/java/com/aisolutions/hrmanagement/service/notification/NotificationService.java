package com.aisolutions.hrmanagement.service.notification;

import com.aisolutions.hrmanagement.dto.NotificationDTO;
import com.aisolutions.hrmanagement.repository.NotificationRepository;
import com.aisolutions.hrmanagement.repository.StaffClaimDetailRepository;
import com.aisolutions.hrmanagement.repository.StaffClaimRepository;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Notification read/mark-read use cases for the HRMS bell.
 * Writing notification rows is done by the action hooks via
 * {@link NotificationRepository#create}.
 */
@ApplicationScoped
public class NotificationService {

    /** HRMS module id — the bell only surfaces this module's rows. */
    public static final String HR_MODULE_ID = "mod18";

    // Deep-link resolution reuses what the subject templates already carry — the
    // claim PERIOD in an approval notice, the RECEIPT NUMBER in a rejection notice — so no
    // reference column is needed. Keep these in sync with the notification wording.
    private static final Pattern APPROVED_SUBJECT = Pattern.compile("Your (\\S+) claim is approved");
    private static final Pattern REJECT_SUBJECT   = Pattern.compile("receipt (\\S+) is rejected");

    @Inject NotificationRepository repo;
    @Inject StaffClaimRepository claimRepo;
    @Inject StaffClaimDetailRepository detailRepo;

    public Uni<List<NotificationDTO>> listForStaff(String staffId, String moduleId) {
        return repo.findForStaff(staffId, moduleOrDefault(moduleId))
            .map(list -> list.stream().map(NotificationDTO::fromEntity).collect(Collectors.toList()));
    }

    public Uni<Long> countUnread(String staffId, String moduleId) {
        return repo.countUnread(staffId, moduleOrDefault(moduleId));
    }

    /** Flip all of a staff member's unread notifications to Read; returns how many changed. */
    public Uni<Integer> markAllRead(String staffId, String moduleId) {
        return repo.markAllRead(staffId, moduleOrDefault(moduleId));
    }

    /** Flips one to Read; returns the updated DTO, or null if not found / not the caller's. */
    public Uni<NotificationDTO> markRead(Long id, String staffId) {
        return repo.markRead(id, staffId)
            .map(n -> n == null ? null : NotificationDTO.fromEntity(n));
    }

    /**
     * The claim a notification points at, so the bell can deep-link to it. Reuses the
     * period (approval notice) or receipt number (rejection notice) already in the message
     * — no reference column. Returns null when nothing resolves; the caller then falls back
     * to the claims list. Coupled to the subject wording (see the patterns above).
     */
    public Uni<Long> resolveTargetClaimId(Long notificationId) {
        return repo.findById(notificationId).flatMap(n -> {
            if (n == null) {
                return Uni.createFrom().nullItem();
            }
            // Preferred: the claim id stored on the notification at creation — no parsing.
            Long ref = parseLongOrNull(n.getReferenceNo());
            if (ref != null) {
                return Uni.createFrom().item(ref);
            }
            // Fallback for notifications written before ReferenceNo existed: read the text.
            String subject = n.getNotificationSubject() == null ? "" : n.getNotificationSubject();
            String staff = n.getNotifyStaff();
            if (staff == null || staff.isBlank()) {
                return Uni.createFrom().nullItem();
            }
            Matcher approved = APPROVED_SUBJECT.matcher(subject);
            if (approved.find()) {
                return claimRepo.findIdByStaffAndPeriod(staff, approved.group(1));
            }
            Matcher rejected = REJECT_SUBJECT.matcher(subject);
            if (rejected.find()) {
                return detailRepo.findClaimIdByStaffAndReceiptNumber(staff, rejected.group(1));
            }
            return Uni.createFrom().nullItem();
        });
    }

    private static String moduleOrDefault(String moduleId) {
        return (moduleId == null || moduleId.isBlank()) ? HR_MODULE_ID : moduleId;
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
