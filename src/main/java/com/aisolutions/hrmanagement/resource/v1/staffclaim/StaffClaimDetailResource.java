package com.aisolutions.hrmanagement.resource.v1.staffclaim;

import com.aisolutions.hrmanagement.dto.OcrReceiptResultDTO;
import com.aisolutions.hrmanagement.dto.StaffClaimDetailDTO;
import com.aisolutions.hrmanagement.service.attachment.AttachmentService;
import com.aisolutions.hrmanagement.service.ocr.ReceiptOcrService;
import com.aisolutions.hrmanagement.service.staffclaim.StaffClaimDetailService;

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
 * Endpoints:
 *   POST  /api/v1/staff-claims-det              — create claim with optional photo (multipart)
 *   GET   /api/v1/staff-claims-det/{id}         — fetch single claim
 *   GET   /api/v1/staff-claims-det?staffId=X    — list claims for a staff member
 *   GET   /api/v1/staff-claims-det/current-month?staffId=X — current month list
 *   GET   /api/v1/staff-claims-det/{id}/photo   — retrieve receipt photo bytes
 */
@Path("/api/v1/staff-claims-det")
@Produces(MediaType.APPLICATION_JSON)
public class StaffClaimDetailResource {

    @Inject StaffClaimDetailService claimService;
    @Inject AttachmentService attachmentService;
    @Inject ReceiptOcrService ocrService;

    // ─────────────────────────────────────────────────────────
    //  CREATE (multipart with optional photo)
    // ─────────────────────────────────────────────────────────

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Uni<Response> createClaim(
            @RestForm("claim") String claimJson,
            @RestForm("photo") FileUpload photo) {

        StaffClaimDetailDTO dto;
        try {
            dto = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .readValue(claimJson, StaffClaimDetailDTO.class);
        } catch (Exception e) {
            return Uni.createFrom().item(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid claim JSON: " + e.getMessage()))
                    .build());
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
                    Response.serverError()
                        .entity(Map.of("error", "Failed to read photo: " + e.getMessage()))
                        .build());
            }
        }

        return claimService.createClaim(dto, photoBytes, photoName, photoType)
            .map(saved -> Response.status(Response.Status.CREATED).entity(saved).build())
            .onFailure(IllegalArgumentException.class).recoverWithItem(err ->
                Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", err.getMessage()))
                    .build())
            .onFailure().recoverWithItem(err -> {
                System.err.println("Error creating claim: " + err.getMessage());
                err.printStackTrace();
                return Response.serverError()
                    .entity(Map.of("error", err.getMessage()))
                    .build();
            });
    }

    // ─────────────────────────────────────────────────────────
    //  READ
    // ─────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────
    //  SCAN RECEIPT — OpenAI Vision OCR
    // ─────────────────────────────────────────────────────────

    /**
     * POST /api/v1/staff-claims-det/scan-receipt
     *
     * Accepts a receipt photo and extracts: merchantName, receiptNumber,
     * receiptDate, receiptAmount using GPT-4o Vision.
     * Returns OcrReceiptResultDTO for the frontend to pre-fill the claim form.
     */
    @POST
    @Path("/scan-receipt")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Uni<Response> scanReceipt(
            @RestForm("photo") FileUpload photo) {

        if (photo == null) {
            return Uni.createFrom().item(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Photo is required"))
                    .build());
        }

        byte[] imageBytes;
        try {
            imageBytes = Files.readAllBytes(photo.uploadedFile());
        } catch (IOException e) {
            return Uni.createFrom().item(
                Response.serverError()
                    .entity(Map.of("error", "Failed to read photo: " + e.getMessage()))
                    .build());
        }

        String mimeType = photo.contentType() != null ? photo.contentType() : "image/jpeg";

        return ocrService.extractFromImage(imageBytes, mimeType)
            .map(result -> Response.ok(result).build())
            .onFailure().recoverWithItem(err -> {
                System.err.println("[ScanReceipt] Error: " + err.getMessage());
                OcrReceiptResultDTO errResult = new OcrReceiptResultDTO();
                errResult.setSuccess(false);
                errResult.setErrorMessage(err.getMessage());
                return Response.serverError().entity(errResult).build();
            });
    }

    @GET
    @Path("/{id}")
    public Uni<Response> getById(@PathParam("id") Long id) {
        return claimService.getById(id)
            .map(dto -> dto == null
                ? Response.status(Response.Status.NOT_FOUND).build()
                : Response.ok(dto).build());
    }

    @GET
    public Uni<Response> listByStaff(@QueryParam("staffId") String staffId) {
        if (staffId == null || staffId.isBlank()) {
            return Uni.createFrom().item(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "staffId is required"))
                    .build());
        }
        return claimService.listByStaff(staffId)
            .map(list -> Response.ok(list).build());
    }

    @GET
    @Path("/current-month")
    public Uni<Response> listCurrentMonth(@QueryParam("staffId") String staffId) {
        if (staffId == null || staffId.isBlank()) {
            return Uni.createFrom().item(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "staffId is required"))
                    .build());
        }
        return claimService.listCurrentMonth(staffId)
            .map(list -> Response.ok(list).build());
    }

    @GET
    @Path("/{id}/photo")
    public Uni<Response> getPhoto(@PathParam("id") Long id) {
        return attachmentService.getAttachments(StaffClaimDetailService.MODULE_TYPE, String.valueOf(id))
            .flatMap(atts -> {
                if (atts == null || atts.isEmpty()) {
                    return Uni.createFrom().item(
                        Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "No photo for this claim"))
                            .build());
                }
                var att = atts.get(0);
                return attachmentService.downloadFile(att.getUniqId())
                    .map(bytes -> Response.ok(bytes)
                        .type(att.getContentType() != null ? att.getContentType() : "application/octet-stream")
                        .header("Content-Disposition",
                                "inline; filename=\"" + att.getOriginalName() + "\"")
                        .build());
            })
            .onFailure().recoverWithItem(err ->
                Response.serverError()
                    .entity(Map.of("error", err.getMessage()))
                    .build());
    }
}
