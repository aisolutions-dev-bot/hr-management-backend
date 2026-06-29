package com.aisolutions.hrmanagement.service;

import com.aisolutions.hrmanagement.dto.UserDTO;
import com.aisolutions.hrmanagement.service.auth.JwtClaimsExtractor;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Resolves the currently authenticated user from the JWT in the incoming
 * request (no external auth call). Used for audit fields (EntryStaff,
 * LastEditStaff). Falls back to "SYSTEM" when no staffId is present.
 */
@ApplicationScoped
public class CurrentUserService {

    public static final String SYSTEM_USER = "SYSTEM";

    @Inject
    JwtClaimsExtractor jwtClaimsExtractor;

    /** Current user's staffId, or "SYSTEM" when the request carries no identity. */
    public Uni<String> getCurrentUserLoginId() {
        return Uni.createFrom().item(resolveStaffId());
    }

    /** Current user as a UserDTO; never null — staffId defaults to "SYSTEM". */
    public Uni<UserDTO> getCurrentUser() {
        UserDTO user = new UserDTO();
        user.setStaffId(resolveStaffId());
        return Uni.createFrom().item(user);
    }

    private String resolveStaffId() {
        String staffId = jwtClaimsExtractor.extractStaffId();
        return (staffId == null || staffId.isBlank()) ? SYSTEM_USER : staffId;
    }
}
