package com.aisolutions.hrmanagement.resource.v1.notification;

import com.aisolutions.hrmanagement.service.CurrentUserService;
import com.aisolutions.hrmanagement.service.notification.NotificationService;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * On-screen notification endpoints.
 *
 *   GET  /api/v1/notifications?staffId=X[&moduleId=mod18]  — a staff member's notifications
 *   GET  /api/v1/notifications/unread-count?staffId=X      — unread badge count
 *   POST /api/v1/notifications/{id}/read                   — flip one to Read
 *
 * staffId falls back to the JWT identity when the query param is absent, so a
 * caller can only ever see (and read) their own notifications.
 */
@Path("/api/v1/notifications")
@Produces(MediaType.APPLICATION_JSON)
public class NotificationResource {

    @Inject NotificationService notificationService;
    @Inject CurrentUserService currentUserService;

    // ── LIST ──
    @GET
    public Uni<Response> list(@QueryParam("staffId") String staffId,
                              @QueryParam("moduleId") String moduleId) {
        return resolveStaff(staffId).flatMap(sid ->
                notificationService.listForStaff(sid, moduleId)
                    .map(list -> Response.ok(list).build()))
            .onFailure().recoverWithItem(NotificationResource::toError);
    }

    // ── UNREAD COUNT (bell badge) ──
    @GET
    @Path("/unread-count")
    public Uni<Response> unreadCount(@QueryParam("staffId") String staffId,
                                     @QueryParam("moduleId") String moduleId) {
        return resolveStaff(staffId).flatMap(sid ->
                notificationService.countUnread(sid, moduleId)
                    .map(count -> Response.ok(Map.of("unread", count)).build()))
            .onFailure().recoverWithItem(NotificationResource::toError);
    }

    // ── MARK READ ──
    // POST (not PATCH): RESTEasy Reactive has no built-in @PATCH, and the rest of
    // this API uses POST for state changes (e.g. /submit).
    @POST
    @Path("/{id}/read")
    public Uni<Response> markRead(@PathParam("id") Long id,
                                  @QueryParam("staffId") String staffId) {
        return resolveStaff(staffId).flatMap(sid ->
                notificationService.markRead(id, sid)
                    .map(dto -> dto == null
                        ? Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "Notification not found")).build()
                        : Response.ok(dto).build()))
            .onFailure().recoverWithItem(NotificationResource::toError);
    }

    // ── MARK ALL READ ──
    @POST
    @Path("/read-all")
    public Uni<Response> markAllRead(@QueryParam("staffId") String staffId,
                                     @QueryParam("moduleId") String moduleId) {
        return resolveStaff(staffId).flatMap(sid ->
                notificationService.markAllRead(sid, moduleId)
                    .map(count -> Response.ok(Map.of("updated", count)).build()))
            .onFailure().recoverWithItem(NotificationResource::toError);
    }

    // ── DEEP-LINK TARGET ──
    // Resolves the claim a notification refers to (from the period/receipt in its text),
    // so the bell can open it. { "claimId": <id|null> }.
    @GET
    @Path("/{id}/target")
    public Uni<Response> target(@PathParam("id") Long id) {
        return notificationService.resolveTargetClaimId(id)
            .map(claimId -> {
                Map<String, Object> body = new java.util.HashMap<>();
                body.put("claimId", claimId);
                return Response.ok(body).build();
            })
            .onFailure().recoverWithItem(NotificationResource::toError);
    }

    /** Query param wins when present; otherwise fall back to the JWT identity. */
    private Uni<String> resolveStaff(String staffId) {
        if (staffId != null && !staffId.isBlank()) {
            return Uni.createFrom().item(staffId);
        }
        return currentUserService.getCurrentUserLoginId();
    }

    private static Response toError(Throwable err) {
        System.err.println("[Notification] " + err.getMessage());
        err.printStackTrace();
        return Response.serverError().entity(Map.of("error", err.getMessage())).build();
    }
}
