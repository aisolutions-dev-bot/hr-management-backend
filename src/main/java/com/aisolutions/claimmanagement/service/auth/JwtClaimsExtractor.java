package com.aisolutions.claimmanagement.service.auth;

import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Extracts claims (staffId) directly from the JWT in the incoming Authorization
 * header — no external API call. The token is issued by the organization auth
 * service and forwarded by the frontend as a Bearer token.
 */
@ApplicationScoped
public class JwtClaimsExtractor {

    @Inject
    RoutingContext routingContext;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtClaims extract() {
        try {
            String header = routingContext.request().getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) return JwtClaims.empty();

            String[] parts = header.substring(7).split("\\.");
            if (parts.length != 3) return JwtClaims.empty();

            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            Map<String, Object> claims = objectMapper.readValue(payloadBytes,
                new TypeReference<Map<String, Object>>() {});

            String staffId = (String) claims.getOrDefault("staffId", "");

            return new JwtClaims(staffId);

        } catch (Exception e) {
            return JwtClaims.empty();
        }
    }

    public String extractStaffId() {
        return extract().staffId();
    }

    public record JwtClaims(String staffId) {
        static JwtClaims empty() {
            return new JwtClaims("");
        }
    }
}
