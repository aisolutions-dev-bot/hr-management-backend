package com.aisolutions.hrmanagement.resource.v1.ocrtraining;

import com.aisolutions.hrmanagement.dto.OcrReceiptResultDTO;
import com.aisolutions.hrmanagement.dto.RecordCorrectionRequestDTO;
import com.aisolutions.hrmanagement.service.ocrtraining.OcrTrainingService;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Endpoints:
 *   POST   /api/v1/ocr-training/apply-training       — enhance OCR result using learned data
 *   POST   /api/v1/ocr-training/record-correction    — record a scan + save for learning
 *   GET    /api/v1/ocr-training/stats                — dashboard stats
 *   GET    /api/v1/ocr-training/aliases              — list merchant aliases
 *   GET    /api/v1/ocr-training/merchant-rules       — list per-merchant rules
 *   GET    /api/v1/ocr-training/global-rules         — list global field rules
 *   GET    /api/v1/ocr-training/corrections          — list correction history
 *   DELETE /api/v1/ocr-training/aliases/{id}
 *   DELETE /api/v1/ocr-training/merchant-rules/{id}
 *   DELETE /api/v1/ocr-training/global-rules/{id}
 *   DELETE /api/v1/ocr-training/corrections/{id}
 *   DELETE /api/v1/ocr-training/all                  — wipe everything
 */
@Path("/api/v1/ocr-training")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OcrTrainingResource {

    @Inject OcrTrainingService trainingService;

    @POST
    @Path("/apply-training")
    public Uni<Response> applyTraining(OcrReceiptResultDTO input) {
        return trainingService.applyTraining(input)
            .map(enhanced -> Response.ok(enhanced).build())
            .onFailure().recoverWithItem(err ->
                Response.serverError()
                    .entity(Map.of("error", err.getMessage()))
                    .build());
    }

    @POST
    @Path("/record-correction")
    public Uni<Response> recordCorrection(RecordCorrectionRequestDTO request) {
        return trainingService.recordCorrection(request)
            .replaceWith(Response.ok(Map.of("success", true)).build())
            .onFailure(IllegalArgumentException.class).recoverWithItem(err ->
                Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", err.getMessage()))
                    .build())
            .onFailure().recoverWithItem(err -> {
                System.err.println("[OcrTraining] Error recording correction: " + err.getMessage());
                err.printStackTrace();
                return Response.serverError()
                    .entity(Map.of("error", err.getMessage()))
                    .build();
            });
    }

    @GET
    @Path("/stats")
    public Uni<Response> getStats() {
        return trainingService.getStats()
            .map(s -> Response.ok(s).build())
            .onFailure().recoverWithItem(err ->
                Response.serverError()
                    .entity(Map.of("error", err.getMessage()))
                    .build());
    }

    @GET
    @Path("/aliases")
    public Uni<Response> listAliases() {
        return trainingService.listAliases().map(list -> Response.ok(list).build());
    }

    @GET
    @Path("/merchant-rules")
    public Uni<Response> listMerchantRules() {
        return trainingService.listMerchantRules().map(list -> Response.ok(list).build());
    }

    @GET
    @Path("/global-rules")
    public Uni<Response> listGlobalRules() {
        return trainingService.listGlobalRules().map(list -> Response.ok(list).build());
    }

    @GET
    @Path("/corrections")
    public Uni<Response> listCorrections() {
        return trainingService.listCorrections().map(list -> Response.ok(list).build());
    }

    @DELETE
    @Path("/aliases/{id}")
    public Uni<Response> deleteAlias(@PathParam("id") Long id) {
        return trainingService.deleteAlias(id)
            .map(deleted -> deleted
                ? Response.ok(Map.of("success", true)).build()
                : Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/merchant-rules/{id}")
    public Uni<Response> deleteMerchantRule(@PathParam("id") Long id) {
        return trainingService.deleteMerchantRule(id)
            .map(deleted -> deleted
                ? Response.ok(Map.of("success", true)).build()
                : Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/global-rules/{id}")
    public Uni<Response> deleteGlobalRule(@PathParam("id") Long id) {
        return trainingService.deleteGlobalRule(id)
            .map(deleted -> deleted
                ? Response.ok(Map.of("success", true)).build()
                : Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/corrections/{id}")
    public Uni<Response> deleteCorrection(@PathParam("id") Long id) {
        return trainingService.deleteCorrection(id)
            .map(deleted -> deleted
                ? Response.ok(Map.of("success", true)).build()
                : Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/all")
    public Uni<Response> clearAll() {
        return trainingService.clearAllTrainingData()
            .replaceWith(Response.ok(Map.of("success", true, "message", "All training data cleared")).build())
            .onFailure().recoverWithItem(err ->
                Response.serverError()
                    .entity(Map.of("error", err.getMessage()))
                    .build());
    }
}
