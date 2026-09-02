package com.aisolutions.hrmanagement.service.auth;

import com.aisolutions.hrmanagement.repository.StaffRepository;
import com.aisolutions.hrmanagement.service.CurrentUserService;
import com.aisolutions.shared.identity.IdentityClaims;
import com.aisolutions.shared.identity.IdentityClaimsExtractor;
import com.aisolutions.shared.tenancy.CompanyPoolManager;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Per-request gate enforcing the HRMS access codes server-side by reading
 * m07GroupAuthorityAccess on the caller's tenant pool. Fails closed — a missing
 * group or an ungranted code returns 403 before the action runs.
 */
@ApplicationScoped
public class AccessControlService {

    private static final String MODULE_ID = "mod18";

    /** Claim submission sub-module (claims + OCR training). */
    public static final String CLAIM_SUBMISSION = "a1803";
    /** Leave submission sub-module. */
    public static final String LEAVE_SUBMISSION = "a1804";

    @Inject IdentityClaimsExtractor identityClaimsExtractor;
    @Inject StaffRepository staffRepository;
    @Inject CompanyPoolManager companyPoolManager;
    @Inject CurrentUserService currentUserService;

    /** Runs {@code action} only when the caller's group holds {@code accessCode}; otherwise 403. */
    public Uni<Response> gate(String accessCode, Supplier<Uni<Response>> action) {
        return hasCode(accessCode).flatMap(granted -> granted
            ? action.get()
            : Uni.createFrom().item(
                Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("message",
                        "You do not have permission for this action (" + accessCode + ")."))
                    .build()));
    }

    /** True when the caller's group has {@code accessCode} granted for mod18. Fail-closed on any error. */
    public Uni<Boolean> hasCode(String accessCode) {
        IdentityClaims claims = identityClaimsExtractor.extract();
        String group = claims.groupAuthority();
        if (group == null || group.isBlank()) {
            return Uni.createFrom().item(false);
        }
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> staffRepository.hasAccessCode(pool, group, MODULE_ID, accessCode))
            .onFailure().recoverWithItem(false);
    }
}
