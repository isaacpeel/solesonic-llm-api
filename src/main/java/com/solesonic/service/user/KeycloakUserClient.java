package com.solesonic.service.user;

import com.solesonic.model.user.KeycloakAccount;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static com.solesonic.config.keycloak.KeycloakAdminConfig.KEYCLOAK_ADMIN_REST_CLIENT;

/**
 * Looks users up in Keycloak. Only a {@code 404} for the user means the account is gone; every
 * other failure throws, so an outage can never be mistaken for a deletion.
 */
@Service
public class KeycloakUserClient {
    private final RestClient keycloakAdminRestClient;

    public KeycloakUserClient(@Qualifier(KEYCLOAK_ADMIN_REST_CLIENT) RestClient keycloakAdminRestClient) {
        this.keycloakAdminRestClient = keycloakAdminRestClient;
    }

    /**
     * Proves the admin URI names a realm this client can read. A wrong realm answers {@code 404} to
     * every user lookup, which would otherwise read as every user having been removed.
     */
    public void verifyRealm() {
        keycloakAdminRestClient.get()
                .uri("/users/count")
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * @return the account, or empty when Keycloak has no user with this id
     */
    public Optional<KeycloakAccount> find(UUID userId) {
        return keycloakAdminRestClient.get()
                .uri("/users/{userId}", userId)
                .exchange((_, response) -> {
                    if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                        return Optional.empty();
                    }

                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("Keycloak answered " + response.getStatusCode().value() + " for a user lookup");
                    }

                    return Optional.ofNullable(response.bodyTo(KeycloakAccount.class));
                });
    }
}
