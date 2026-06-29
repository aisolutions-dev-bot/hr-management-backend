package com.aisolutions.hrmanagement.resource.v1.dropdown;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.aisolutions.hrmanagement.service.dropdown.DropdownService;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/dropdowns")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DropdownResource {

    @Inject
    DropdownService dropdownService;

    /**
     * GET /api/v1/dropdowns?types=staff,departments,contacttypes
     * Get specific dropdowns by type
     */
   @GET
    public Uni<Response> getDropdownsByTypes(@QueryParam("types") String types) {
        if (types == null || types.isBlank()) {
            return Uni.createFrom().item(Response.ok(Map.of()).build());
        }

        List<String> typeList = Arrays.asList(types.split(","));
        
        return dropdownService.getDropdownsByTypeKeys(typeList)
            .onItem().transform(dropdowns -> Response.ok(dropdowns).build())
            .onFailure().recoverWithItem(error -> 
                Response.serverError().entity(Map.of("error", error.getMessage())).build()
            );
    }

    @GET
    @Path("/projects/open")
    public Uni<Response> getOpenProjects() {
        return dropdownService.getOpenProjects()
            .onItem().transform(list -> Response.ok(list).build())
            .onFailure().recoverWithItem(error -> 
                Response.serverError().entity(Map.of("error", error.getMessage())).build()
            );
    }

    // ============================================================
    // Individual dropdown endpoints (optional)
    // ============================================================

    // @GET
    // @Path("/projects")
    // public Uni<Response> getProjects() {
    //     return dropdownService.getProjects()
    //         .onItem().transform(list -> Response.ok(list).build())
    //         .onFailure().recoverWithItem(error -> 
    //             Response.serverError().entity(Map.of("error", error.getMessage())).build()
    //         );
    // }
}
    
