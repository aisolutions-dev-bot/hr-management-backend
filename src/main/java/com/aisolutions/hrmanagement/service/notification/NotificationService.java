package com.aisolutions.hrmanagement.service.notification;

import com.aisolutions.hrmanagement.dto.NotificationDTO;
import com.aisolutions.hrmanagement.repository.NotificationRepository;
import com.aisolutions.hrmanagement.repository.StaffClaimDetailRepository;
import com.aisolutions.hrmanagement.repository.StaffClaimRepository;
import com.aisolutions.shared.tenancy.CompanyPoolManager;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class NotificationService {

    public static final String HR_MODULE_ID = "mod18";

    private static final Pattern APPROVED_SUBJECT = Pattern.compile("Your (\\S+) claim is approved");
    private static final Pattern REJECT_SUBJECT   = Pattern.compile("receipt (\\S+) is rejected");

    @Inject NotificationRepository repo;
    @Inject StaffClaimRepository claimRepo;
    @Inject StaffClaimDetailRepository detailRepo;
    @Inject CompanyPoolManager companyPoolManager;
    @Inject com.aisolutions.hrmanagement.service.CurrentUserService currentUserService;

    public Uni<List<NotificationDTO>> listForStaff(String staffId, String moduleId) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> repo.findForStaff(pool, staffId, moduleOrDefault(moduleId)))
            .map(list -> list.stream().map(NotificationDTO::fromEntity).collect(Collectors.toList()));
    }

    public Uni<Long> countUnread(String staffId, String moduleId) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> repo.countUnread(pool, staffId, moduleOrDefault(moduleId)));
    }

    public Uni<Integer> markAllRead(String staffId, String moduleId) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> repo.markAllRead(pool, staffId, moduleOrDefault(moduleId)));
    }

    public Uni<NotificationDTO> markRead(Long id, String staffId) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> repo.markRead(pool, id, staffId))
            .map(n -> n == null ? null : NotificationDTO.fromEntity(n));
    }

    public Uni<Long> resolveTargetClaimId(Long notificationId) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> repo.findById(pool, notificationId).flatMap(n -> {
                if (n == null) {
                    return Uni.createFrom().nullItem();
                }
                Long ref = parseLongOrNull(n.getReferenceNo());
                if (ref != null) {
                    return Uni.createFrom().item(ref);
                }
                String subject = n.getNotificationSubject() == null ? "" : n.getNotificationSubject();
                String staff = n.getNotifyStaff();
                if (staff == null || staff.isBlank()) {
                    return Uni.createFrom().nullItem();
                }
                Matcher approved = APPROVED_SUBJECT.matcher(subject);
                if (approved.find()) {
                    return claimRepo.findIdByStaffAndPeriod(pool, staff, approved.group(1));
                }
                Matcher rejected = REJECT_SUBJECT.matcher(subject);
                if (rejected.find()) {
                    return detailRepo.findClaimIdByStaffAndReceiptNumber(pool, staff, rejected.group(1));
                }
                return Uni.createFrom().nullItem();
            }));
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
