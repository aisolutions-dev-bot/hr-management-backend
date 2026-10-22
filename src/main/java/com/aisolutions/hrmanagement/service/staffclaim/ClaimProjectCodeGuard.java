package com.aisolutions.hrmanagement.service.staffclaim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.hrmanagement.dto.StaffClaimDetailDTO;
import com.aisolutions.hrmanagement.repository.ProjectCodeRepository;
import com.aisolutions.hrmanagement.util.StringNormalizer;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;

/** Enforces that a receipt on a company project carries its Activity Code and Process Code. */
@ApplicationScoped
public class ClaimProjectCodeGuard {

    @Inject
    ProjectCodeRepository projectCodeRepository;

    /** Fails with a clear message when a company-project receipt lacks its Activity Code or Process Code. */
    public Uni<Void> requireCodes(SqlClient tx, StaffClaimDetailDTO dto) {
        if (!StringNormalizer.isBlank(dto.getActivityCode()) && !StringNormalizer.isBlank(dto.getProcessCode())) {
            return Uni.createFrom().voidItem();
        }
        String projectCode = dto.getProjectId().trim();
        return projectCodeRepository.existsByCode(tx, projectCode).flatMap(isProject -> {
            if (!isProject) {
                return Uni.createFrom().voidItem();
            }
            return Uni.createFrom()
                    .failure(new IllegalArgumentException(
                            "Activity Code and Process Code are required for project " + projectCode));
        });
    }
}
