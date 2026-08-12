package com.solesonic.service.google;

import com.solesonic.exception.google.GoogleTokenException;
import com.solesonic.model.google.auth.GoogleAccessToken;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

class GoogleTokenRefreshServiceTest {

    private static final String STORED_REFRESH_TOKEN = "stored-refresh-token";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private GoogleTokenRefreshService googleTokenRefreshService(String responseBody) {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://oauth2.googleapis.com")
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(responseBody)
                        .build()))
                .build();

        GoogleTokenRefreshService googleTokenRefreshService =
                new GoogleTokenRefreshService(webClient, objectMapper);

        ReflectionTestUtils.setField(googleTokenRefreshService, "clientId", "client-id");
        ReflectionTestUtils.setField(googleTokenRefreshService, "clientSecret", "client-secret");

        return googleTokenRefreshService;
    }

    private static GoogleAccessToken storedToken() {
        return GoogleAccessToken.builder()
                .userId(UUID.randomUUID())
                .accessToken("expired-access-token")
                .refreshToken(STORED_REFRESH_TOKEN)
                .expiresIn(3600)
                .created(ZonedDateTime.now().minusHours(2))
                .build();
    }

    /**
     * The behaviour with no Atlassian equivalent. Google omits {@code refresh_token} from a refresh
     * response, so persisting the response as-is would blank the only credential able to renew the
     * account.
     */
    @Test
    void keepsTheStoredRefreshTokenWhenGoogleOmitsOne() {
        String responseBody = """
                {"access_token":"new-access-token","expires_in":3599,"token_type":"Bearer"}""";

        GoogleAccessToken refreshed = googleTokenRefreshService(responseBody).refresh(storedToken());

        assertThat(refreshed.accessToken()).isEqualTo("new-access-token");
        assertThat(refreshed.refreshToken()).isEqualTo(STORED_REFRESH_TOKEN);
        assertThat(refreshed.expiresIn()).isEqualTo(3599);
        assertThat(refreshed.created()).isNotNull();
        assertThat(refreshed.isExpired()).isFalse();
    }

    @Test
    void takesTheNewRefreshTokenWhenGoogleRotatesIt() {
        String responseBody = """
                {"access_token":"new-access-token","refresh_token":"rotated","expires_in":3599}""";

        GoogleAccessToken refreshed = googleTokenRefreshService(responseBody).refresh(storedToken());

        assertThat(refreshed.refreshToken()).isEqualTo("rotated");
    }

    @Test
    void carriesTheUserIdForwardSoTheStoredTokenStaysAttributed() {
        String responseBody = """
                {"access_token":"new-access-token","expires_in":3599}""";

        GoogleAccessToken storedToken = storedToken();

        GoogleAccessToken refreshed = googleTokenRefreshService(responseBody).refresh(storedToken);

        assertThat(refreshed.userId()).isEqualTo(storedToken.userId());
    }

    /**
     * A revoked grant is not a transient failure — retrying cannot fix it, only re-consent can, so
     * it must not be reported as retriable.
     */
    @Test
    void treatsInvalidGrantAsNonRetriable() {
        String responseBody = """
                {"error":"invalid_grant","error_description":"Token has been expired or revoked."}""";

        GoogleTokenRefreshService googleTokenRefreshService = googleTokenRefreshService(responseBody);
        GoogleAccessToken storedToken = storedToken();

        assertThatThrownBy(() -> googleTokenRefreshService.refresh(storedToken))
                .isInstanceOf(GoogleTokenException.class)
                .satisfies(thrown -> {
                    GoogleTokenException googleTokenException = (GoogleTokenException) thrown;
                    assertThat(googleTokenException.getErrorCode()).isEqualTo(BAD_REQUEST);
                    assertThat(googleTokenException.isRetriable()).isFalse();
                });
    }

    @Test
    void treatsAnUnrecognisedErrorAsRetriable() {
        String responseBody = """
                {"error":"internal_failure","error_description":"Backend error."}""";

        GoogleTokenRefreshService googleTokenRefreshService = googleTokenRefreshService(responseBody);
        GoogleAccessToken storedToken = storedToken();

        assertThatThrownBy(() -> googleTokenRefreshService.refresh(storedToken))
                .isInstanceOf(GoogleTokenException.class)
                .satisfies(thrown -> {
                    GoogleTokenException googleTokenException = (GoogleTokenException) thrown;
                    assertThat(googleTokenException.getErrorCode()).isEqualTo(SERVICE_UNAVAILABLE);
                    assertThat(googleTokenException.isRetriable()).isTrue();
                });
    }

    @Test
    void refusesToRefreshWithoutAStoredRefreshToken() {
        GoogleTokenRefreshService googleTokenRefreshService = googleTokenRefreshService("{}");

        GoogleAccessToken withoutRefreshToken = GoogleAccessToken.builder()
                .accessToken("access-token")
                .build();

        assertThatThrownBy(() -> googleTokenRefreshService.refresh(withoutRefreshToken))
                .isInstanceOf(GoogleTokenException.class)
                .satisfies(thrown -> assertThat(((GoogleTokenException) thrown).isRetriable()).isFalse());
    }
}
