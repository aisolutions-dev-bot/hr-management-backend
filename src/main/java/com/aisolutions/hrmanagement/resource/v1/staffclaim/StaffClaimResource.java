package com.aisolutions.hrmanagement.resource.v1.staffclaim;

import com.aisolutions.hrmanagement.dto.StaffClaimDTO;
import com.aisolutions.hrmanagement.dto.StaffClaimDetailDTO;
import com.aisolutions.hrmanagement.service.auth.AccessControlService;
import com.aisolutions.hrmanagement.service.staffclaim.StaffClaimService;
import com.aisolutions.hrmanagement.service.useractionlog.UserActionLogService.DeviceInfo;
import com.aisolutions.hrmanagement.util.DeviceInfoExtractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Claim HEADER endpoints (batch model — one header + many line items).
 *
 *   POST   /api/v1/staff-claims                       — create a draft claim (JSON: claimPeriod)
 *   POST   /api/v1/staff-claims/current-draft         — get-or-create this month's open draft
 *   GET    /api/v1/staff-claims?staffId=X             — list a staff member's claims (header level)
 *   GET    /api/v1/staff-claims/{id}                  — fetch one claim WITH its line items
 *   POST   /api/v1/staff-claims/{id}/lines            — add a line (+optional receipt photo, multipart)
 *   DELETE /api/v1/staff-claims/{id}/lines/{lineId}   — remove a line (draft only)
 *   POST   /api/v1/staff-claims/{id}/submit           — submit the claim for approval
 *   POST   /api/v1/staff-claims/submit-batch          — submit several drafts at once
 */
@Path("/api/v1/staff-claims")
@Produces(MediaType.APPLICATION_JSON)
public class StaffClaimResource {

    @Inject StaffClaimService claimService;
    @Inject AccessControlService access;

    /** The claim sub-module is gated by the claim-submission access code (mod18). */
    private static final String CODE = AccessControlService.CLAIM_SUBMISSION;

    @Context HttpHeaders headers;
    @Context HttpServerRequest request;

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    // ── CREATE DRAFT ──
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> createDraft(StaffClaimDTO dto) {
        return access.gate(CODE, () -> claimService.createDraft(dto == null ? new StaffClaimDTO() : dto)
                .map(saved -> Response.status(Response.Status.CREATED).entity(saved).build())
                .onFailure().recoverWithItem(StaffClaimResource::toError));
    }

    // ── GET-OR-CREATE THIS MONTH'S OPEN DRAFT ──
    // POST, not GET: it creates a header when the staff member has none for the month.
    @POST
    @Path("/current-draft")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> currentDraft(StaffClaimDTO dto) {
        return access.gate(CODE, () -> claimService.getOrCreateCurrentDraft(dto == null ? null : dto.getStaffId())
                .map(claim -> Response.ok(claim).build())
                .onFailure().recoverWithItem(StaffClaimResource::toError));
    }

    // ── LIST (header level) ──
    @GET
    public Uni<Response> listByStaff(@QueryParam("staffId") String staffId) {
        return access.gate(CODE, () -> {
            if (staffId == null || staffId.isBlank()) {
                return Uni.createFrom().item(badRequest("staffId is required"));
            }
            return claimService.listByStaff(staffId).map(list -> Response.ok(list).build());
        });
    }

    // ── GET ONE (with lines) ──
    @GET
    @Path("/{id}")
    public Uni<Response> getOne(@PathParam("id") Long id) {
        return access.gate(CODE, () -> claimService.getWithLines(id)
                .map(dto -> dto == null
                        ? Response.status(Response.Status.NOT_FOUND).build()
                        : Response.ok(dto).build()));
    }

    // ── ADD LINE (multipart: claim JSON + optional photo) ──
    @POST
    @Path("/{id}/lines")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Uni<Response> addLine(
            @PathParam("id") Long headerId,
            @RestForm("claim") String claimJson,
            @RestForm("photo") FileUpload photo) {

        return access.gate(CODE, () -> {
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

            DeviceInfo deviceInfo = DeviceInfoExtractor.extract(headers, request);
            return claimService.addLine(headerId, dto, photoBytes, photoName, photoType, deviceInfo)
                    .map(saved -> Response.status(Response.Status.CREATED).entity(saved).build())
                    .onFailure().recoverWithItem(StaffClaimResource::toError);
        });
    }

    // ── REMOVE LINE ──
    @DELETE
    @Path("/{id}/lines/{lineId}")
    public Uni<Response> removeLine(@PathParam("id") Long headerId,
                                    @PathParam("lineId") Long lineId) {
        return access.gate(CODE, () -> claimService.removeLine(headerId, lineId)
                .map(v -> Response.noContent().build())
                .onFailure().recoverWithItem(StaffClaimResource::toError));
    }

    // ── SUBMIT ──
    @POST
    @Path("/{id}/submit")
    public Uni<Response> submit(@PathParam("id") Long headerId) {
        return access.gate(CODE, () -> {
            DeviceInfo deviceInfo = DeviceInfoExtractor.extract(headers, request);
            return claimService.submit(headerId, deviceInfo)
                    .map(saved -> Response.ok(saved).build())
                    .onFailure().recoverWithItem(StaffClaimResource::toError);
        });
    }

    // ── ACCEPT REJECTION (staff voids a rejected receipt) ──
    @POST
    @Path("/{id}/lines/{lineId}/accept-rejection")
    public Uni<Response> acceptRejection(@PathParam("id") Long headerId,
                                         @PathParam("lineId") Long lineId) {
        return access.gate(CODE, () -> {
            DeviceInfo deviceInfo = DeviceInfoExtractor.extract(headers, request);
            return claimService.acceptRejection(headerId, lineId, deviceInfo)
                    .map(saved -> Response.ok(saved).build())
                    .onFailure().recoverWithItem(StaffClaimResource::toError);
        });
    }

    // ── RESUBMIT REJECTED RECEIPT (fix/appeal → back to PENDING) ──
    // Body (optional): { "appealDescription": "..." }
    @POST
    @Path("/{id}/lines/{lineId}/resubmit")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> resubmitRejectedLine(@PathParam("id") Long headerId,
                                              @PathParam("lineId") Long lineId,
                                              Map<String, String> body) {
        return access.gate(CODE, () -> {
            String appeal = body == null ? null : body.get("appealDescription");
            DeviceInfo deviceInfo = DeviceInfoExtractor.extract(headers, request);
            return claimService.resubmitRejectedLine(headerId, lineId, appeal, deviceInfo)
                    .map(saved -> Response.ok(saved).build())
                    .onFailure().recoverWithItem(StaffClaimResource::toError);
        });
    }

    // ── EDIT & RESUBMIT REJECTED RECEIPT (scenario 1 — full edit + optional new photo) ──
    // multipart: claim JSON + optional photo (a new photo is kept as a new version).
    @PUT
    @Path("/{id}/lines/{lineId}/edit-resubmit")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Uni<Response> editRejectedLine(
            @PathParam("id") Long headerId,
            @PathParam("lineId") Long lineId,
            @RestForm("claim") String claimJson,
            @RestForm("photo") FileUpload photo) {

        return access.gate(CODE, () -> {
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

            return claimService.editRejectedLine(headerId, lineId, dto, photoBytes, photoName, photoType)
                    .map(saved -> Response.ok(saved).build())
                    .onFailure().recoverWithItem(StaffClaimResource::toError);
        });
    }

    // ── SUBMIT BATCH (body: [4, 5]) ──
    @POST
    @Path("/submit-batch")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> submitBatch(List<Long> claimIds) {
        return access.gate(CODE, () -> {
            DeviceInfo deviceInfo = DeviceInfoExtractor.extract(headers, request);
            return claimService.submitBatch(claimIds, deviceInfo)
                    .map(saved -> Response.ok(saved).build())
                    .onFailure().recoverWithItem(StaffClaimResource::toError);
        });
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
