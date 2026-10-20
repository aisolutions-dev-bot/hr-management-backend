package com.aisolutions.hrmanagement.service.staffclaim;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.hrmanagement.client.ProjectClient;
import com.aisolutions.hrmanagement.dto.DropdownOptionDTO;
import com.aisolutions.hrmanagement.dto.ProjectOptionDTO;
import com.aisolutions.hrmanagement.dto.StaffClaimDTO;
import com.aisolutions.hrmanagement.entity.StaffClaim;
import com.aisolutions.hrmanagement.repository.ClaimProjectRepository;
import com.aisolutions.hrmanagement.repository.StaffClaimRepository;
import com.aisolutions.hrmanagement.repository.StaffRepository;
import com.aisolutions.hrmanagement.service.CurrentUserService;
import com.aisolutions.hrmanagement.service.DocNoSeqService;
import com.aisolutions.hrmanagement.util.StringNormalizer;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import com.aisolutions.shared.util.DateUtil;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Opens numbered draft claims for a claimant and claim title. The same claimant and title reuse the
 * open draft; a different pair gets a new draft with its own claim number.
 */
@ApplicationScoped
public class StaffClaimDraftService {

    private static final Logger LOG = Logger.getLogger(StaffClaimDraftService.class);

    private static final int LEN_STAFF_ID = 25;
    private static final int LEN_PROJECT_ID = 25;

    @Inject
    CompanyPoolManager companyPoolManager;

    @Inject
    CurrentUserService currentUserService;

    @Inject
    StaffClaimRepository headerRepo;

    @Inject
    StaffRepository staffRepo;

    @Inject
    ClaimProjectRepository projectRepo;

    @Inject
    @RestClient
    ProjectClient projectClient;

    @Inject
    DocNoSeqService docNoSeqService;

    @Inject
    StaffClaimService claimService;

    /** Returns the open draft for this claimant and title, creating a numbered one when none exists. */
    public Uni<StaffClaimDTO> openDraft(String claimStaffId, String projectId) {
        return currentUserService.getCurrentUser().flatMap(user -> {
            String me = user == null ? null : user.getStaffId();
            if (StringNormalizer.isBlank(me)) {
                return Uni.<StaffClaimDTO>createFrom()
                        .failure(new IllegalArgumentException("Cannot resolve the logged-in staff"));
            }
            String claimant = StringNormalizer.isBlank(claimStaffId) ? me : claimStaffId.trim();
            String title = projectId == null ? "" : projectId.trim();
            if (title.length() > LEN_PROJECT_ID) {
                return Uni.<StaffClaimDTO>createFrom()
                        .failure(new IllegalArgumentException(
                                "Claim title must be " + LEN_PROJECT_ID + " characters or fewer"));
            }
            return companyPoolManager
                    .poolFor(currentUserService.getCurrentCompanyId())
                    .flatMap(pool -> requireValidClaimant(pool, me, claimant)
                            .flatMap(v -> pool.withTransaction(tx -> findOrCreate(tx, me, claimant, title)))
                            .flatMap(h -> claimService.getWithLines(h.getUniqId())));
        });
    }

    /** Open projects to pick a claim title from; empty when the Project module is not subscribed. */
    public Uni<List<DropdownOptionDTO>> projectOptions(String authorization) {
        return companyPoolManager
                .poolFor(currentUserService.getCurrentCompanyId())
                .flatMap(pool -> projectRepo
                        .isProjectModuleSubscribed(pool)
                        .flatMap(subscribed -> subscribed
                                ? fetchOpenProjects(authorization)
                                : Uni.createFrom().item(List.<DropdownOptionDTO>of())));
    }

    private Uni<List<DropdownOptionDTO>> fetchOpenProjects(String authorization) {
        return projectClient
                .getActiveDropdown(authorization)
                .map(projects -> projects.stream()
                        .map(p -> new DropdownOptionDTO(p.getProjectCode(), projectLabel(p)))
                        .toList())
                .onFailure()
                .recoverWithItem(err -> {
                    LOG.warnf("Project list unavailable: %s", err.getMessage());
                    return List.<DropdownOptionDTO>of();
                });
    }

    private static String projectLabel(ProjectOptionDTO p) {
        String name = p.getProjectName();
        return name == null || name.isBlank() ? p.getProjectCode() : p.getProjectCode() + " · " + name;
    }

    private Uni<Void> requireValidClaimant(SqlClient pool, String me, String claimant) {
        if (claimant.equalsIgnoreCase(me)) {
            return Uni.createFrom().voidItem();
        }
        return staffRepo.findClaimantOptions(pool).flatMap(options -> {
            boolean allowed = options.stream().anyMatch(o -> claimant.equalsIgnoreCase(o.getValue()));
            return allowed
                    ? Uni.createFrom().voidItem()
                    : Uni.<Void>createFrom().failure(new IllegalArgumentException("Claimant Staff is not valid"));
        });
    }

    private Uni<StaffClaim> findOrCreate(SqlClient tx, String me, String claimant, String title) {
        return docNoSeqService
                .claimNoLeadIn(tx)
                .flatMap(leadIn -> headerRepo
                        .findOpenNumberedDraft(tx, me, claimant, title, leadIn)
                        .flatMap(existing -> {
                            if (existing != null) {
                                return Uni.createFrom().item(existing);
                            }
                            LocalDateTime now = DateUtil.nowSGT();
                            return docNoSeqService
                                    .nextClaimNo(tx, YearMonth.from(now), me)
                                    .flatMap(claimNo -> {
                                        StaffClaim draft = newDraft(me, claimant, title, now);
                                        draft.setClaimPeriod(claimNo);
                                        return headerRepo.save(tx, draft);
                                    });
                        }));
    }

    private static StaffClaim newDraft(String me, String claimant, String title, LocalDateTime now) {
        StaffClaim h = new StaffClaim();
        h.setStaffId(StringNormalizer.truncate(me, LEN_STAFF_ID));
        h.setClaimStaffId(StringNormalizer.truncate(claimant, LEN_STAFF_ID));
        h.setProjectId(title.isEmpty() ? null : title);
        h.setClaimAmount(BigDecimal.ZERO);
        h.setStatus(StaffClaimService.STATUS_DRAFT);
        h.setEntryStaff(h.getStaffId());
        h.setEntryDate(now);
        h.setLastEditStaff(h.getStaffId());
        h.setLastEditDate(now);
        return h;
    }
}
