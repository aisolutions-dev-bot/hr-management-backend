package com.aisolutions.hrmanagement.resource.v1.staffclaim;

import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.aisolutions.hrmanagement.dto.StaffClaimDTO;
import com.aisolutions.hrmanagement.service.auth.AccessControlService;
import com.aisolutions.hrmanagement.service.staffclaim.StaffClaimDraftService;
import io.smallrye.mutiny.Uni;

/** Opens the draft for a claimant and claim title, and lists the projects a claim title can be picked from. */
@Path("/api/v1/staff-claims")
@Produces(MediaType.APPLICATION_JSON)
public class StaffClaimDraftResource {

    @Inject
    StaffClaimDraftService draftService;

    @Inject
    AccessControlService access;

    @POST
    @Path("/drafts")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> openDraft(StaffClaimDTO dto) {
        String claimant = dto == null ? null : dto.getClaimStaffId();
        String title = dto == null ? null : dto.getProjectId();
        return access.gate(
                AccessControlService.CLAIM_SUBMISSION,
                () -> draftService
                        .openDraft(claimant, title)
                        .map(claim -> Response.ok(claim).build())
                        .onFailure(IllegalArgumentException.class)
                        .recoverWithItem(err -> Response.status(Response.Status.BAD_REQUEST)
                                .entity(Map.of("error", err.getMessage()))
                                .build()));
    }

    @GET
    @Path("/project-options")
    public Uni<Response> projectOptions(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization) {
        return access.gate(
                AccessControlService.CLAIM_SUBMISSION,
                () -> draftService
                        .projectOptions(authorization)
                        .map(options -> Response.ok(options).build()));
    }
}
