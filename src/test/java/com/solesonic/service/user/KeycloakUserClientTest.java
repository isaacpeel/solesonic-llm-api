package com.solesonic.service.user;

import com.solesonic.model.user.KeycloakAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KeycloakUserClientTest {
    private static final String ADMIN_URI = "https://auth.example.com/admin/realms/izzy-bot";
    private static final UUID USER_ID = UUID.randomUUID();

    private MockRestServiceServer server;
    private KeycloakUserClient keycloakUserClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ADMIN_URI);
        server = MockRestServiceServer.bindTo(builder).build();
        keycloakUserClient = new KeycloakUserClient(builder.build());
    }

    @Test
    void readsTheAccountIgnoringFieldsItDoesNotUse() {
        server.expect(requestTo(ADMIN_URI + "/users/" + USER_ID))
                .andRespond(withSuccess("""
                        {"id":"%s","username":"sam","email":"sam@example.com","enabled":true,"emailVerified":true,"createdTimestamp":1}
                        """.formatted(USER_ID), MediaType.APPLICATION_JSON));

        assertThat(keycloakUserClient.find(USER_ID)).contains(new KeycloakAccount("sam", "sam@example.com", true));
    }

    @Test
    void aMissingUserIsEmpty() {
        server.expect(requestTo(ADMIN_URI + "/users/" + USER_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(keycloakUserClient.find(USER_ID)).isEmpty();
    }

    /**
     * Only a 404 means gone. A refusal or an outage must throw, never read as a deletion.
     */
    @Test
    void anyOtherFailureThrows() {
        server.expect(requestTo(ADMIN_URI + "/users/" + USER_ID))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> keycloakUserClient.find(USER_ID)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnreadableRealmFailsVerification() {
        server.expect(requestTo(ADMIN_URI + "/users/count"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> keycloakUserClient.verifyRealm()).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aReadableRealmVerifies() {
        server.expect(requestTo(ADMIN_URI + "/users/count"))
                .andRespond(withSuccess("10", MediaType.APPLICATION_JSON));

        keycloakUserClient.verifyRealm();

        server.verify();
    }
}
