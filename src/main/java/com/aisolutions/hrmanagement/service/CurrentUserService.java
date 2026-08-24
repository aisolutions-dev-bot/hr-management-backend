package com.aisolutions.hrmanagement.service;

import com.aisolutions.hrmanagement.dto.UserDTO;
import com.aisolutions.shared.identity.IdentityClaimsExtractor;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retrieves the currently logged-in user by extracting claims directly from
 * the JWT token in the incoming request, no external API call required.
 *
 * Uses the shared {@link IdentityClaimsExtractor} which reads from the
 * CDI-produced {@code JsonWebToken} after quarkus-smallrye-jwt has
 * verified the signature against org-api's published JWKS.
 */
@ApplicationScoped
public class CurrentUserService {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserService.class);

    public static final String SYSTEM_USER = "SYSTEM";

    @Inject
    IdentityClaimsExtractor identityClaimsExtractor;

    /**
     * Returns the current user's staffId from the verified JWT, or
     * {@link #SYSTEM_USER} when no identity is present.
     *
     * @return the staffId wrapped in a Uni
     */
    public Uni<String> getCurrentUserLoginId() {
        return Uni.createFrom().item(resolveStaffId());
    }

    /**
     * Returns the current user as a UserDTO, with staffId populated
     * from the verified JWT. Never null.
     *
     * @return the UserDTO wrapped in a Uni
     */
    public Uni<UserDTO> getCurrentUser() {
        UserDTO user = new UserDTO();
        user.setStaffId(resolveStaffId());
        return Uni.createFrom().item(user);
    }

    /**
     * The company routing claim for the current request, blank when
     * absent. CompanyPoolManager treats a blank value as "route to
     * the default database".
     *
     * @return the companyId from the JWT, or empty string
     */
    public String getCurrentCompanyId() {
        return identityClaimsExtractor.extract().companyId();
    }

    /**
     * Extracts the staffId from the JWT, falling back to SYSTEM_USER
     * when the claim is absent or blank.
     *
     * @return the resolved staff identifier
     */
    private String resolveStaffId() {
        String staffId = identityClaimsExtractor.extract().staffId();
        if (staffId == null || staffId.isBlank()) {
            log.warn("No staffId found in JWT, request may be missing Authorization header");
            return SYSTEM_USER;
        }
        return staffId;
    }
}
