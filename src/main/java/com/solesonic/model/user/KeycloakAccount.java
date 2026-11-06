package com.solesonic.model.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The part of Keycloak's {@code UserRepresentation} the lifecycle sweep reads. An account counts as
 * active unless Keycloak says outright that it is disabled.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakAccount(String username, String email, Boolean enabled) {

    public boolean isActive() {
        return !Boolean.FALSE.equals(enabled);
    }
}
