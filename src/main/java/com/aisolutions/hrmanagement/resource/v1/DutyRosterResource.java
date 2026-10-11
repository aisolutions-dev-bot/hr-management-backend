package com.aisolutions.hrmanagement.resource.v1;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.aisolutions.hrmanagement.entity.DutyRoster;
import com.aisolutions.hrmanagement.repository.DutyRosterRepository;
import com.aisolutions.hrmanagement.service.CurrentUserService;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import io.smallrye.mutiny.Uni;

/**
 * Duty roster — read-only, self-service.
 *
 *   GET /api/v1/duty-roster/my?from=&to=   — the caller's own roster rows in a date range
 *
 * Rows are created/maintained by HR elsewhere (hr-administration-backend); this service
 * never writes to m18DutyRoster. staffId is always resolved from the verified JWT —
 * never accepted from a query param — so a caller can only ever see their own roster.
 */
@Path("/api/v1/duty-roster")
@Produces(MediaType.APPLICATION_JSON)
public class DutyRosterResource {

    @Inject
    DutyRosterRepository dutyRosterRepository;

    @Inject
    CurrentUserService currentUserService;

    @Inject
    CompanyPoolManager companyPoolManager;

    @GET
    @Path("/my")
    public Uni<Response> my(@QueryParam("from") String from, @QueryParam("to") String to) {
        LocalDate fromDate;
        LocalDate toDate;
        try {
            if (from == null || from.isBlank() || to == null || to.isBlank()) {
                return Uni.createFrom().item(badRequest("Both 'from' and 'to' are required (yyyy-MM-dd)"));
            }
            fromDate = LocalDate.parse(from.trim());
            toDate = LocalDate.parse(to.trim());
        } catch (DateTimeParseException e) {
            return Uni.createFrom().item(badRequest("Invalid date (expected yyyy-MM-dd)"));
        }

        return currentUserService
                .getCurrentUserLoginId()
                .flatMap(staffId -> companyPoolManager
                        .poolFor(currentUserService.getCurrentCompanyId())
                        .flatMap(pool -> dutyRosterRepository.findByStaffAndDateRange(pool, staffId, fromDate, toDate)))
                .map((List<DutyRoster> list) -> Response.ok(list).build())
                .onFailure()
                .recoverWithItem(DutyRosterResource::toError);
    }

    private static Response toError(Throwable err) {
        System.err.println("[DutyRoster] " + err.getMessage());
        err.printStackTrace();
        return Response.serverError()
                .entity(Map.of("error", String.valueOf(err.getMessage())))
                .build();
    }

    private static Response badRequest(String msg) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", msg))
                .build();
    }
}
