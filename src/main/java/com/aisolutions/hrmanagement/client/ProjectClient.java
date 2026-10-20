package com.aisolutions.hrmanagement.client;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import com.aisolutions.hrmanagement.dto.ProjectOptionDTO;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** REST client for the organization API's project dropdown; the caller's token selects the company. */
@Path("/api/projects")
@RegisterRestClient(configKey = "organization-api")
public interface ProjectClient {

    /** Open and in-progress projects, ordered by name. */
    @GET
    @Path("/dropdown/active")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<List<ProjectOptionDTO>> getActiveDropdown(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization);
}
