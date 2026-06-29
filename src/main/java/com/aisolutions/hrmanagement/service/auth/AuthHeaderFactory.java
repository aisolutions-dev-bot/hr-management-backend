package com.aisolutions.hrmanagement.service.auth;

import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;

@ApplicationScoped
public class AuthHeaderFactory implements ClientHeadersFactory {

    @Override
    public MultivaluedMap<String, String> update(
            MultivaluedMap<String, String> incoming,
            MultivaluedMap<String, String> clientOutgoing) {

        String auth = incoming.getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null) {
            clientOutgoing.putSingle(HttpHeaders.AUTHORIZATION, auth);
        }
        return clientOutgoing;
    }
}
