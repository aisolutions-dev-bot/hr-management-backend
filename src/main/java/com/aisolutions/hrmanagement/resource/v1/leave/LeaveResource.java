package com.aisolutions.hrmanagement.resource.v1.leave;

import com.aisolutions.hrmanagement.dto.LeaveApplicationDTO;
import com.aisolutions.hrmanagement.service.leave.LeaveService;
import com.aisolutions.hrmanagement.service.useractionlog.UserActionLogService.DeviceInfo;
import com.aisolutions.hrmanagement.util.DeviceInfoExtractor;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Staff leave applications (HRMS side).
 *
 *   GET  /api/v1/leaves/profile?staffId=X          — Step 1 prefill (name + department)
 *   GET  /api/v1/leaves/leave-types                — Step 2 leave-type dropdown
 *   GET  /api/v1/leaves/approvers                   — Step 4 approver dropdown
 *   GET  /api/v1/leaves/balance?leaveType=AL        — balance for the current user (warn-only)
 *   GET  /api/v1/leaves/balances                    — all-types balance summary (dashboard)
 *   GET  /api/v1/leaves/cancelable?leaveType=AL     — Step 3 (Cancel): the user's cancelable leaves
 *   GET  /api/v1/leaves/working-days?from&to&half   — Step 3 working-days preview
 *   GET  /api/v1/leaves?staffId=X                   — the user's leave applications
 *   GET  /api/v1/leaves/{id}                        — one application (with names resolved)
 *   POST /api/v1/leaves                             — submit an application (apply or cancel)
 */
@Path("/api/v1/leaves")
@Produces(MediaType.APPLICATION_JSON)
public class LeaveResource {

    @Inject LeaveService leaveService;

    @Context HttpHeaders headers;
    @Context HttpServerRequest request;

    // ── Step 1 prefill ──
    @GET
    @Path("/profile")
    public Uni<Response> profile(@QueryParam("staffId") String staffId) {
        return leaveService.getProfile(staffId)
                .map(dto -> Response.ok(dto).build())
                .onFailure().recoverWithItem(LeaveResource::toError);
    }

    // ── Step 2 dropdown ──
    @GET
    @Path("/leave-types")
    public Uni<Response> leaveTypes() {
        return leaveService.getLeaveTypeOptions().map(list -> Response.ok(list).build());
    }

    // ── Step 4 dropdown ──
    @GET
    @Path("/approvers")
    public Uni<Response> approvers() {
        return leaveService.getApproverOptions().map(list -> Response.ok(list).build());
    }

    // ── Balance (warn-only) ──
    @GET
    @Path("/balance")
    public Uni<Response> balance(@QueryParam("staffId") String staffId,
                                 @QueryParam("leaveType") String leaveType) {
        return leaveService.getBalance(staffId, leaveType)
                .map(dto -> Response.ok(dto).build())
                .onFailure().recoverWithItem(LeaveResource::toError);
    }

    // ── All-types balance summary (dashboard) ──
    @GET
    @Path("/balances")
    public Uni<Response> balances(@QueryParam("staffId") String staffId) {
        return leaveService.getBalances(staffId)
                .map(list -> Response.ok(list).build())
                .onFailure().recoverWithItem(LeaveResource::toError);
    }

    // ── Cancelable leaves (Step 3, Cancel) ──
    @GET
    @Path("/cancelable")
    public Uni<Response> cancelable(@QueryParam("staffId") String staffId,
                                    @QueryParam("leaveType") String leaveType) {
        return leaveService.getCancelable(staffId, leaveType)
                .map(list -> Response.ok(list).build())
                .onFailure().recoverWithItem(LeaveResource::toError);
    }

    // ── Working-days preview (Step 3) ──
    @GET
    @Path("/working-days")
    public Uni<Response> workingDays(@QueryParam("from") String from,
                                     @QueryParam("to") String to,
                                     @QueryParam("half") String half) {
        LocalDate fromDate;
        LocalDate toDate;
        try {
            fromDate = (from == null || from.isBlank()) ? null : LocalDate.parse(from);
            toDate   = (to == null || to.isBlank()) ? null : LocalDate.parse(to);
        } catch (Exception e) {
            return Uni.createFrom().item(badRequest("Invalid date (expected yyyy-MM-dd)"));
        }
        BigDecimal days = LeaveService.workingDays(fromDate, toDate, half);
        Map<String, Object> body = new HashMap<>();
        body.put("totalDays", days);
        return Uni.createFrom().item(Response.ok(body).build());
    }

    // ── List ──
    @GET
    public Uni<Response> listByStaff(@QueryParam("staffId") String staffId) {
        if (staffId == null || staffId.isBlank()) {
            return Uni.createFrom().item(badRequest("staffId is required"));
        }
        return leaveService.listByStaff(staffId).map(list -> Response.ok(list).build());
    }

    // ── Get one ──
    @GET
    @Path("/{id}")
    public Uni<Response> getOne(@PathParam("id") Long id) {
        return leaveService.getOne(id)
                .map(dto -> dto == null
                        ? Response.status(Response.Status.NOT_FOUND).build()
                        : Response.ok(dto).build());
    }

    // ── Submit ──
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> submit(LeaveApplicationDTO dto) {
        if (dto == null) {
            return Uni.createFrom().item(badRequest("A leave application body is required"));
        }
        DeviceInfo deviceInfo = DeviceInfoExtractor.extract(headers, request);
        return leaveService.submitApplication(dto, deviceInfo)
                .map(saved -> Response.status(Response.Status.CREATED).entity(saved).build())
                .onFailure().recoverWithItem(LeaveResource::toError);
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
        System.err.println("[Leave] " + err.getMessage());
        err.printStackTrace();
        return Response.serverError().entity(Map.of("error", String.valueOf(err.getMessage()))).build();
    }

    private static Response badRequest(String msg) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", msg)).build();
    }
}
