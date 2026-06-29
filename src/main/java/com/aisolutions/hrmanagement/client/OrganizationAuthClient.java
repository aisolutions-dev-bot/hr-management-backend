package com.aisolutions.hrmanagement.client;

import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import com.aisolutions.hrmanagement.dto.UserDTO;
import com.aisolutions.hrmanagement.service.auth.AuthHeaderFactory;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/auth")
@RegisterRestClient(configKey = "organization-api")
@RegisterClientHeaders(AuthHeaderFactory.class)
public interface OrganizationAuthClient {

    @GET
    @Path("/me")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<UserDTO> getCurrentUser();
}
