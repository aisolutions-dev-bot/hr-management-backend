package com.aisolutions.claimmanagement.common;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.resteasy.reactive.ClientWebApplicationException;

/**
 * Propagates the real status of a downstream REST-client failure instead of
 * collapsing it to a 500. In particular, when the Organization Auth REST client
 * gets a 401 (expired/invalid session), this returns 401 to the frontend so the
 * auth interceptor can refresh or redirect to login — matching the other modules.
 */
@Provider
public class ClientWebApplicationExceptionMapper implements ExceptionMapper<ClientWebApplicationException> {

    @Override
    public Response toResponse(ClientWebApplicationException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof WebApplicationException wae) {
            return Response.status(wae.getResponse().getStatus()).build();
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }
}
