package com.solesonic.service.xero;

import com.solesonic.exception.xero.XeroTokenException;
import com.solesonic.model.xero.auth.XeroAccessToken;
import com.solesonic.model.xero.auth.XeroAuthRequest;
import com.solesonic.model.xero.auth.XeroConnection;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.user.UserPreferencesService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.UUID;

import static com.solesonic.config.xero.XeroConstants.XERO_AUTH_WEB_CLIENT;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * The connect half of the Xero integration: send the user to consent, then turn the authorization
 * code they come back with into a stored, tenant-resolved connection.
 * <p>
 * Every failure in here leaves as a {@link XeroTokenException}, including the ones Xero did not
 * choose to send — an unreachable host, or a body that is not JSON at all. That is deliberate:
 * {@code GeneralExceptionHandler} renders any other {@code RuntimeException} as {@code 200 OK}
 * carrying a chat message, and this endpoint answers {@code 204} on success, so an uncaught
 * transport or parse failure would reach the connecting browser looking like a completed connection.
 */
@Service
public class XeroAuthService {
    private static final Logger log = LoggerFactory.getLogger(XeroAuthService.class);

    public static final String AUTHORIZATION_CODE_GRANT = "authorization_code";

    private static final String CONNECT_PATH_SEGMENT = "connect";
    private static final String TOKEN_PATH_SEGMENT = "token";
    private static final String CONNECTIONS_PATH = "/connections";

    private static final String CLIENT_ID_PARAM = "client_id";
    private static final String REDIRECT_URI_PARAM = "redirect_uri";
    private static final String RESPONSE_TYPE_PARAM = "response_type";
    private static final String SCOPE_PARAM = "scope";
    private static final String STATE_PARAM = "state";

    private static final String RESPONSE_TYPE = "code";
    private static final String BEARER = "Bearer ";

    private static final String MISSING_CODE_ERROR = "missing_authorization_code";
    private static final String NO_CONNECTION_ERROR = "no_xero_organisation_granted";
    private static final String UNREACHABLE_ERROR = "xero_unreachable";
    private static final String UNREADABLE_ERROR = "xero_response_unreadable";
    private static final String CONNECTIONS_FAILED_ERROR = "xero_connections_lookup_failed";

    private static final String TOKEN_RESPONSE_DESCRIPTION = "token response";
    private static final String CONNECTIONS_RESPONSE_DESCRIPTION = "connections response";

    @Value("${xero.oauth.auth-uri}")
    private String xeroAuthUri;

    @Value("${xero.oauth.client-id}")
    private String clientId;

    @Value("${xero.oauth.client-secret}")
    private String clientSecret;

    @Value("${xero.api.uri}")
    private String xeroApiUri;

    @Value("${xero.api.auth.callback.uri}")
    private String authCallbackUri;

    private final UserRequestContext userRequestContext;
    private final UserPreferencesService userPreferencesService;
    private final ObjectMapper objectMapper;

    private final WebClient authWebClient;

    public XeroAuthService(UserRequestContext userRequestContext,
                           UserPreferencesService userPreferencesService,
                           ObjectMapper objectMapper,
                           @Qualifier(XERO_AUTH_WEB_CLIENT) WebClient authWebClient) {
        this.userRequestContext = userRequestContext;
        this.userPreferencesService = userPreferencesService;
        this.objectMapper = objectMapper;
        this.authWebClient = authWebClient;
    }

    /**
     * The URL the user visits to grant access. {@code state} carries the user id for correlation in
     * logs only — the callback resolves the user from its own authenticated request, never from
     * this value, so a forged state cannot attach someone else's Xero organisation.
     */
    public String authUri() {
        UUID userId = userRequestContext.getUserId();
        log.info("Building Xero auth URI for user: {}", userId);

        String uri = UriComponentsBuilder.fromUriString(xeroAuthUri)
                .queryParam(RESPONSE_TYPE_PARAM, RESPONSE_TYPE)
                .queryParam(CLIENT_ID_PARAM, clientId)
                .queryParam(REDIRECT_URI_PARAM, authCallbackUri)
                .queryParam(SCOPE_PARAM, XeroScope.scopeParameter())
                .queryParam(STATE_PARAM, userId)
                .encode()
                .build()
                .toUriString();

        log.info("Built Xero auth URI for user: {}", userId);

        return uri;
    }

    /**
     * Exchanges the authorization code for tokens, resolves which Xero organisation the grant
     * covers, and stores the two together.
     * <p>
     * The tenant lookup is not optional bookkeeping. A Xero token by itself cannot call the
     * Accounting API — every request needs an {@code xero-tenant-id} header, and
     * {@code GET /connections} is the only place that value exists. A grant that resolves to no
     * organisation is therefore rejected rather than stored: persisting it would flip
     * {@code xeroAuthentication} to true and then fail every call made afterwards.
     */
    public void callback(String code) {
        UUID userId = userRequestContext.getUserId();
        log.info("Xero callback for user: {}", userId);

        if (StringUtils.isBlank(code)) {
            log.warn("Xero callback for user {} carried no authorization code", userId);

            throw new XeroTokenException(MISSING_CODE_ERROR, BAD_REQUEST, false);
        }

        XeroAuthRequest xeroAuthRequest = XeroAuthRequest.builder()
                .grantType(AUTHORIZATION_CODE_GRANT)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .code(code)
                .redirectUri(authCallbackUri)
                .build();

        String responseJson = exchangeAuthorizationCode(xeroAuthRequest);

        XeroAccessToken xeroAccessToken =
                read(responseJson, XeroAccessToken.class, TOKEN_RESPONSE_DESCRIPTION);

        if (StringUtils.isNotEmpty(xeroAccessToken.error())) {
            log.warn("Xero token exchange failed for user {}: {} - {}",
                    userId, xeroAccessToken.error(), xeroAccessToken.errorDescription());

            throw new XeroTokenException(xeroAccessToken.error(), BAD_REQUEST, false);
        }

        XeroConnection connection = firstConnection(userId, xeroAccessToken.accessToken());

        XeroAccessToken tokenWithTenant = XeroAccessToken.from(xeroAccessToken)
                .userId(userId)
                .tenantId(connection.tenantId())
                .tenantName(connection.tenantName())
                .build();

        log.info("Saving Xero access token for user: {}", userId);
        log.info("Xero token has refresh token: {}", StringUtils.isNotEmpty(tokenWithTenant.refreshToken()));

        userPreferencesService.save(userId, tokenWithTenant);
    }

    /**
     * The token exchange itself. The response body is taken whatever the status is, because a failed
     * exchange is precisely where the useful detail lives — Xero names {@code invalid_grant} in the
     * body of a 400, and that is what separates "reconnect" from "retry".
     */
    private String exchangeAuthorizationCode(XeroAuthRequest xeroAuthRequest) {
        try {
            return authWebClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .pathSegment(CONNECT_PATH_SEGMENT, TOKEN_PATH_SEGMENT)
                            .build())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(xeroAuthRequest.formData()))
                    .exchangeToMono(response -> response.bodyToMono(String.class))
                    .block();
        } catch (WebClientException webClientException) {
            log.error("Could not reach Xero's token endpoint", webClientException);

            throw new XeroTokenException(UNREACHABLE_ERROR, SERVICE_UNAVAILABLE, true, webClientException);
        }
    }

    /**
     * The organisation this connection will act on.
     * <p>
     * Xero's consent screen cannot be constrained to a single organisation up front, so a user who
     * ticked several comes back with several. One organisation per user is the deliberate choice:
     * the first is taken and the rest are logged, because there is nowhere in this integration for a
     * second tenant to live.
     * <p>
     * Runs on the auth client rather than the API client, against an absolute URI on a different
     * host. The API client is the one that carries a <em>stored</em> token and the tenant header
     * derived from it, and at this point in the flow neither exists — this call is what produces the
     * tenant. Because the auth client deliberately has no error-handling filter, the status is
     * checked here instead: unlike the token endpoint, a non-2xx from {@code /connections} carries
     * no detail worth reading, and parsing an error body as an array of connections would surface as
     * an unhelpful parse failure.
     */
    private XeroConnection firstConnection(UUID userId, String accessToken) {
        URI connectionsUri = URI.create(xeroApiUri + CONNECTIONS_PATH);

        String connectionsJson = connections(connectionsUri, accessToken);

        XeroConnection[] connections =
                read(connectionsJson, XeroConnection[].class, CONNECTIONS_RESPONSE_DESCRIPTION);

        if (connections.length == 0) {
            log.warn("Xero grant for user {} covers no organisation", userId);

            throw new XeroTokenException(NO_CONNECTION_ERROR, BAD_REQUEST, false);
        }

        if (connections.length > 1) {
            log.warn("Xero grant for user {} covers {} organisations; using the first one",
                    userId, connections.length);
        }

        return connections[0];
    }

    private String connections(URI connectionsUri, String accessToken) {
        try {
            return authWebClient.get()
                    .uri(connectionsUri)
                    .header(AUTHORIZATION, BEARER + accessToken)
                    .exchangeToMono(XeroAuthService::connectionsBody)
                    .block();
        } catch (WebClientException webClientException) {
            log.error("Could not reach Xero's connections endpoint", webClientException);

            throw new XeroTokenException(UNREACHABLE_ERROR, SERVICE_UNAVAILABLE, true, webClientException);
        }
    }

    private static Mono<String> connectionsBody(ClientResponse response) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(String.class);
        }

        log.error("Xero connections lookup failed with status {}", response.statusCode());

        return response.releaseBody()
                .then(Mono.error(new XeroTokenException(CONNECTIONS_FAILED_ERROR, SERVICE_UNAVAILABLE, true)));
    }

    /**
     * Jackson 3 throws an <em>unchecked</em> {@link JacksonException}, so a body that is not JSON at
     * all — an outage page from a CDN in front of Xero, say — would otherwise escape this class
     * entirely and be rendered as a success by the generic handler.
     */
    private <T> T read(String json, Class<T> type, String description) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException jacksonException) {
            log.error("Unreadable Xero {}", description, jacksonException);

            throw new XeroTokenException(UNREADABLE_ERROR, SERVICE_UNAVAILABLE, true, jacksonException);
        }
    }
}
