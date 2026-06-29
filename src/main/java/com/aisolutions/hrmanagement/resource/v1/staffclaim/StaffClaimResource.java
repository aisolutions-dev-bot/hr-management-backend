package com.aisolutions.hrmanagement.resource.v1.staffclaim;

import com.aisolutions.hrmanagement.dto.StaffClaimDTO;
import com.aisolutions.hrmanagement.dto.StaffClaimDetailDTO;
import com.aisolutions.hrmanagement.service.staffclaim.StaffClaimService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * Claim HEADER endpoints (batch model — one header + many line items).
 *
 *   POST   /api/v1/staff-claims                       — create a draft claim (JSON: claimPeriod)
 *   GET    /api/v1/staff-claims?staffId=X             — list a staff member's claims (header level)
 *   GET    /api/v1/staff-claims/{id}                  — fetch one claim WITH its line items
 *   POST   /api/v1/staff-claims/{id}/lines            — add a line (+optional receipt photo, multipart)
 *   DELETE /api/v1/staff-claims/{id}/lines/{lineId}   — remove a line (draft only)
 *   POST   /api/v1/staff-claims/{id}/submit           — submit the claim for approval
 */
@Path("/api/v1/staff-claims")
@Produces(MediaType.APPLICATION_JSON)
public class StaffClaimResource {

    @Inject StaffClaimService claimService;

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    // ── CREATE DRAFT ──
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> createDraft(StaffClaimDTO dto) {
        return claimService.createDraft(dto == null ? new StaffClaimDTO() : dto)
                .map(saved -> Response.status(Response.Status.CREATED).entity(saved).build())
                .onFailure().recoverWithItem(StaffClaimResource::toError);
    }

    // ── LIST (header level) ──
    @GET
    public Uni<Response> listByStaff(@QueryParam("staffId") String staffId) {
        if (staffId == null || staffId.isBlank()) {
            return Uni.createFrom().item(badRequest("staffId is required"));
        }
        return claimService.listByStaff(staffId).map(list -> Response.ok(list).build());
    }

    // ── GET ONE (with lines) ──
    @GET
    @Path("/{id}")
    public Uni<Response> getOne(@PathParam("id") Long id) {
        return claimService.getWithLines(id)
                .map(dto -> dto == null
                        ? Response.status(Response.Status.NOT_FOUND).build()
                        : Response.ok(dto).build());
    }

    // ── ADD LINE (multipart: claim JSON + optional photo) ──
    @POST
    @Path("/{id}/lines")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Uni<Response> addLine(
            @PathParam("id") Long headerId,
            @RestForm("claim") String claimJson,
            @RestForm("photo") FileUpload photo) {

        StaffClaimDetailDTO dto;
        try {
            dto = MAPPER.readValue(claimJson, StaffClaimDetailDTO.class);
        } catch (Exception e) {
            return Uni.createFrom().item(badRequest("Invalid claim JSON: " + e.getMessage()));
        }

        byte[] photoBytes = null;
        String photoName = null;
        String photoType = null;
        if (photo != null) {
            try {
                photoBytes = Files.readAllBytes(photo.uploadedFile());
                photoName = photo.fileName();
                photoType = photo.contentType();
            } catch (IOException e) {
                return Uni.createFrom().item(
                        Response.serverError().entity(Map.of("error",
                                "Failed to read photo: " + e.getMessage())).build());
            }
        }

        return claimService.addLine(headerId, dto, photoBytes, photoName, photoType)
                .map(saved -> Response.status(Response.Status.CREATED).entity(saved).build())
                .onFailure().recoverWithItem(StaffClaimResource::toError);
    }

    // ── REMOVE LINE ──
    @DELETE
    @Path("/{id}/lines/{lineId}")
    public Uni<Response> removeLine(@PathParam("id") Long headerId,
                                    @PathParam("lineId") Long lineId) {
        return claimService.removeLine(headerId, lineId)
                .map(v -> Response.noContent().build())
                .onFailure().recoverWithItem(StaffClaimResource::toError);
    }

    // ── SUBMIT ──
    @POST
    @Path("/{id}/submit")
    public Uni<Response> submit(@PathParam("id") Long headerId) {
        return claimService.submit(headerId)
                .map(saved -> Response.ok(saved).build())
                .onFailure().recoverWithItem(StaffClaimResource::toError);
    }

    // ── error mapping ──
    private static Response toError(Throwable err) {
        if (err instanceof NotFoundException) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", err.getMessage())).build();
        }
        if (err instanceof IllegalArgumentException) {
            return badRequest(err.getMessage());
        }
        System.err.println("[StaffClaim] " + err.getMessage());
        err.printStackTrace();
        return Response.serverError().entity(Map.of("error", err.getMessage())).build();
    }

    private static Response badRequest(String msg) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", msg)).build();
    }
}
