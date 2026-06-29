package com.aisolutions.claimmanagement.resource;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/greeting")
@Produces(MediaType.APPLICATION_JSON)
public class GreetingResource {

    @GET
    @Path("/{name}")
    public Greeting hello(@PathParam("name") String name) {
        return new Greeting("hello " + name);
    }

    /* ---- Jackson turns this into JSON ---- */
    public static class Greeting {
        public String message;

        public Greeting(String message) {
            this.message = message;
        }
    }
}