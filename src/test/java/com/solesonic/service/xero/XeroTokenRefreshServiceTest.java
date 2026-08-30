package com.solesonic.service.xero;

import com.solesonic.exception.xero.XeroTokenException;
import com.solesonic.model.xero.auth.XeroAccessToken;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * The refresh half of the Xero integration.
 * <p>
 * The assertion this class exists for is {@link #returnsTheRotatedRefreshTokenNeverTheStoredOne()}:
 * Xero invalidates the previous refresh token the instant a new one is issued, so a carry-forward
 * branch of the kind Google's refresh needs would leave the stored credential permanently dead.
 */
class XeroTokenRefreshServiceTest {

    private static final String OAUTH_BASE_URI = "https://identity.xero.com";
    private static final String CLIENT_ID = "client-id";
    private static final String CLIENT_SECRET = "client-secret";

    private static final String STORED_REFRESH_TOKEN = "stored-refresh-token";

    /** Xero returns a brand-new refresh token on every refresh, and kills the one just used. */
    private static final String REFRESH_RESPONSE = """
            {"access_token":"rotated-access-token","refresh_token":"rotated-refresh-token",\
            "token_type":"Bearer","expires_in":1800,"scope":"accounting.transactions offline_access"}""";

    /** What a CDN in front of Xero serves during an outage: not JSON at all. */
    private static final String OUTAGE_PAGE = "<html><head><title>502 Bad Gateway</title></head></html>";

    private static final BodyInserter.Context BODY_CONTEXT = new BodyInserter.Context() {
        @Override
        public List<HttpMessageWriter<?>> messageWriters() {
            return ExchangeStrategies.withDefaults().messageWriters();
        }

        @Override
        public Optional<ServerHttpRequest> serverRequest() {
            return Optional.empty();
        }

        @Override
        public Map<String, Object> hints() {
            return Map.of();
        }
    };

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final List<ClientRequest> recordedRequests = new ArrayList<>();

    private final UUID userId = UUID.randomUUID();

    private XeroTokenRefreshService xeroTokenRefreshService(String response) {
        return xeroTokenRefreshService(response, HttpStatus.OK);
    }

    private XeroTokenRefreshService xeroTokenRefreshService(String response, HttpStatus status) {
        WebClient authWebClient = WebClient.builder()
                .baseUrl(OAUTH_BASE_URI)
                .exchangeFunction(request -> {
                    recordedRequests.add(request);

                    return Mono.just(ClientResponse.create(status)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(response)
                            .build());
                })
                .build();

        return xeroTokenRefreshService(authWebClient);
    }

    private XeroTokenRefreshService unreachableXeroTokenRefreshService() {
        WebClient authWebClient = WebClient.builder()
                .baseUrl(OAUTH_BASE_URI)
                .exchangeFunction(request -> {
                    recordedRequests.add(request);

                    return Mono.error(new WebClientRequestException(
                            new IOException("connection refused"),
                            POST,
                            URI.create(OAUTH_BASE_URI + "/connect/token"),
                            new HttpHeaders()));
                })
                .build();

        return xeroTokenRefreshService(authWebClient);
    }

    private XeroTokenRefreshService xeroTokenRefreshService(WebClient authWebClient) {
        XeroTokenRefreshService xeroTokenRefreshService =
                new XeroTokenRefreshService(authWebClient, objectMapper);

        ReflectionTestUtils.setField(xeroTokenRefreshService, "clientId", CLIENT_ID);
        ReflectionTestUtils.setField(xeroTokenRefreshService, "clientSecret", CLIENT_SECRET);

        return xeroTokenRefreshService;
    }

    /**
     * A stored connection: expired, as every token older than thirty minutes is, and carrying the
     * tenant resolved during the original consent.
     */
    private XeroAccessToken storedToken() {
        return XeroAccessToken.builder()
                .userId(userId)
                .accessToken("expired-access-token")
                .refreshToken(STORED_REFRESH_TOKEN)
                .tokenType("Bearer")
                .scope("accounting.transactions offline_access")
                .expiresIn(1800)
                .tenantId("tenant-id")
                .tenantName("Demo Company (Global)")
                .created(ZonedDateTime.now().minusHours(2))
                .updated(ZonedDateTime.now().minusHours(2))
                .build();
    }

    /**
     * The reason this service cannot have Google's carry-forward branch. Xero rotates the refresh
     * token on every use and invalidates the previous one immediately, so keeping the stored one
     * would persist a credential that is already dead — and the connection could never renew again.
     */
    @Test
    void returnsTheRotatedRefreshTokenNeverTheStoredOne() {
        XeroAccessToken refreshed = xeroTokenRefreshService(REFRESH_RESPONSE).refresh(storedToken());

        assertThat(refreshed.refreshToken()).isEqualTo("rotated-refresh-token");
        assertThat(refreshed.refreshToken()).isNotEqualTo(STORED_REFRESH_TOKEN);
        assertThat(refreshed.accessToken()).isEqualTo("rotated-access-token");
    }

    @Test
    void sendsTheStoredRefreshTokenUnderTheRefreshGrant() {
        xeroTokenRefreshService(REFRESH_RESPONSE).refresh(storedToken());

        String formBody = formBody(onlyRequest());

        assertThat(formBody).contains("grant_type=refresh_token");
        assertThat(formBody).contains("refresh_token=" + STORED_REFRESH_TOKEN);
        assertThat(formBody).contains("client_id=" + CLIENT_ID);
        assertThat(formBody).contains("client_secret=" + CLIENT_SECRET);
    }

    @Test
    void postsToXerosTokenEndpoint() {
        xeroTokenRefreshService(REFRESH_RESPONSE).refresh(storedToken());

        assertThat(onlyRequest().url().toString()).isEqualTo(OAUTH_BASE_URI + "/connect/token");
        assertThat(onlyRequest().method()).isEqualTo(POST);
    }

    /**
     * {@code tenantId} is not part of Xero's token response — it was resolved once from
     * {@code GET /connections} during consent. Losing it here would break every Accounting API call
     * made after the first refresh, since each needs it on an {@code xero-tenant-id} header.
     */
    @Test
    void carriesTheTenantForwardBecauseXeroDoesNotReturnIt() {
        XeroAccessToken refreshed = xeroTokenRefreshService(REFRESH_RESPONSE).refresh(storedToken());

        assertThat(refreshed.tenantId()).isEqualTo("tenant-id");
        assertThat(refreshed.tenantName()).isEqualTo("Demo Company (Global)");
        assertThat(refreshed.userId()).isEqualTo(userId);
    }

    /**
     * {@link XeroAccessToken#isExpired()} reads a token with no {@code created} as expired, and
     * {@code UserPreferencesService.update} stamps only {@code updated}. Without a {@code created}
     * stamped here, every single call would refresh — and each refresh burns the stored refresh
     * token, so the churn is expensive rather than merely wasteful.
     */
    @Test
    void stampsCreatedSoTheRefreshedTokenIsNotImmediatelySeenAsExpired() {
        XeroAccessToken refreshed = xeroTokenRefreshService(REFRESH_RESPONSE).refresh(storedToken());

        assertThat(refreshed.created()).isNotNull();
        assertThat(refreshed.updated()).isNotNull();
        assertThat(refreshed.isExpired()).isFalse();
    }

    /**
     * A revoked, expired or already-rotated grant. No amount of retrying revives it — only
     * re-consent does — so it must never be reported as retriable.
     */
    @Test
    void treatsInvalidGrantAsNonRetriable() {
        String errorResponse = """
                {"error":"invalid_grant","error_description":"The refresh token is invalid."}""";

        XeroTokenRefreshService xeroTokenRefreshService = xeroTokenRefreshService(errorResponse);
        XeroAccessToken storedToken = storedToken();

        assertThatThrownBy(() -> xeroTokenRefreshService.refresh(storedToken))
                .isInstanceOf(XeroTokenException.class)
                .satisfies(nonRetriable(BAD_REQUEST));
    }

    @Test
    void treatsAnyOtherErrorAsRetriable() {
        String errorResponse = """
                {"error":"temporarily_unavailable","error_description":"Try again later."}""";

        XeroTokenRefreshService xeroTokenRefreshService = xeroTokenRefreshService(errorResponse);
        XeroAccessToken storedToken = storedToken();

        assertThatThrownBy(() -> xeroTokenRefreshService.refresh(storedToken))
                .isInstanceOf(XeroTokenException.class)
                .satisfies(retriable(SERVICE_UNAVAILABLE));
    }

    /**
     * A connection whose refresh token never arrived — the grant was made without
     * {@code offline_access}. There is nothing to send, so no round trip is made.
     */
    @Test
    void refusesAStoredTokenCarryingNoRefreshToken() {
        XeroTokenRefreshService xeroTokenRefreshService = xeroTokenRefreshService(REFRESH_RESPONSE);

        XeroAccessToken withoutRefreshToken = XeroAccessToken.from(storedToken())
                .refreshToken(null)
                .build();

        assertThatThrownBy(() -> xeroTokenRefreshService.refresh(withoutRefreshToken))
                .isInstanceOf(XeroTokenException.class)
                .satisfies(nonRetriable(BAD_REQUEST));

        assertThat(recordedRequests).isEmpty();
    }

    /**
     * Jackson 3 throws an <em>unchecked</em> exception, so an outage page where JSON was expected
     * would otherwise escape this class entirely and reach {@code GeneralExceptionHandler}, which
     * renders any other {@code RuntimeException} as {@code 200 OK} carrying a chat message.
     */
    @Test
    void treatsAnUnreadableResponseAsARetriableUpstreamFailure() {
        XeroTokenRefreshService xeroTokenRefreshService = xeroTokenRefreshService(OUTAGE_PAGE);
        XeroAccessToken storedToken = storedToken();

        assertThatThrownBy(() -> xeroTokenRefreshService.refresh(storedToken))
                .isInstanceOf(XeroTokenException.class)
                .satisfies(retriable(SERVICE_UNAVAILABLE));
    }

    @Test
    void treatsAnUnreachableTokenEndpointAsARetriableUpstreamFailure() {
        XeroTokenRefreshService xeroTokenRefreshService = unreachableXeroTokenRefreshService();
        XeroAccessToken storedToken = storedToken();

        assertThatThrownBy(() -> xeroTokenRefreshService.refresh(storedToken))
                .isInstanceOf(XeroTokenException.class)
                .satisfies(retriable(SERVICE_UNAVAILABLE));
    }

    private ClientRequest onlyRequest() {
        assertThat(recordedRequests).hasSize(1);

        return recordedRequests.getFirst();
    }

    /**
     * Xero's token endpoint takes {@code application/x-www-form-urlencoded}, so the assertion that
     * matters is what the encoded body actually carries rather than what was handed to the builder.
     */
    private static String formBody(ClientRequest clientRequest) {
        MockClientHttpRequest mockClientHttpRequest = new MockClientHttpRequest(POST, URI.create("/"));

        clientRequest.body().insert(mockClientHttpRequest, BODY_CONTEXT).block();

        return mockClientHttpRequest.getBodyAsString().block();
    }

    private static Consumer<Throwable> nonRetriable(HttpStatus expectedStatus) {
        return thrown -> {
            XeroTokenException xeroTokenException = (XeroTokenException) thrown;
            assertThat(xeroTokenException.getErrorCode()).isEqualTo(expectedStatus);
            assertThat(xeroTokenException.isRetriable()).isFalse();
        };
    }

    private static Consumer<Throwable> retriable(HttpStatus expectedStatus) {
        return thrown -> {
            XeroTokenException xeroTokenException = (XeroTokenException) thrown;
            assertThat(xeroTokenException.getErrorCode()).isEqualTo(expectedStatus);
            assertThat(xeroTokenException.isRetriable()).isTrue();
        };
    }
}
