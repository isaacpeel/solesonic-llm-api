package com.solesonic.service.xero;

import com.solesonic.exception.xero.XeroTokenException;
import com.solesonic.model.xero.auth.XeroAccessToken;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.user.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

class XeroAuthServiceTest {

    private static final String AUTHORIZE_URI = "https://login.xero.com/identity/connect/authorize";
    private static final String OAUTH_BASE_URI = "https://identity.xero.com";
    private static final String API_URI = "https://api.xero.com";
    private static final String CALLBACK_URI = "https://example.test/xero/auth/callback";
    private static final String CLIENT_ID = "client-id";
    private static final String CLIENT_SECRET = "client-secret";

    private static final String CONNECTIONS_PATH_MARKER = "connections";

    private static final String TOKEN_RESPONSE = """
            {"access_token":"new-access-token","refresh_token":"new-refresh-token",\
            "token_type":"Bearer","expires_in":1800,"scope":"accounting.transactions offline_access"}""";

    private static final String SINGLE_CONNECTION = """
            [{"id":"connection-id","tenantId":"tenant-id","tenantType":"ORGANISATION",\
            "tenantName":"Demo Company (Global)"}]""";

    private static final String TWO_CONNECTIONS = """
            [{"id":"first-connection","tenantId":"first-tenant","tenantType":"ORGANISATION",\
            "tenantName":"First Org"},\
            {"id":"second-connection","tenantId":"second-tenant","tenantType":"ORGANISATION",\
            "tenantName":"Second Org"}]""";

    /** What a CDN in front of Xero serves during an outage: not JSON at all. */
    private static final String OUTAGE_PAGE = "<html><head><title>502 Bad Gateway</title></head></html>";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final List<ClientRequest> recordedRequests = new ArrayList<>();

    private UserRequestContext userRequestContext;
    private UserPreferencesService userPreferencesService;
    private UUID userId;

    @BeforeEach
    void beforeEach() {
        userId = UUID.randomUUID();
        userRequestContext = mock(UserRequestContext.class);
        userPreferencesService = mock(UserPreferencesService.class);

        when(userRequestContext.getUserId()).thenReturn(userId);
    }

    private XeroAuthService xeroAuthService(String tokenResponse, String connectionsResponse) {
        return xeroAuthService(tokenResponse, connectionsResponse, HttpStatus.OK);
    }

    /**
     * Both Xero calls the connect flow makes carry their own credentials — the token exchange its
     * client secret, {@code GET /connections} the access token it just received — so both travel on
     * the auth client. The stub routes on the path the way the two real endpoints differ, and
     * records every request so a test can assert where it actually went.
     */
    private XeroAuthService xeroAuthService(String tokenResponse,
                                            String connectionsResponse,
                                            HttpStatus connectionsStatus) {
        WebClient authWebClient = WebClient.builder()
                .baseUrl(OAUTH_BASE_URI)
                .exchangeFunction(request -> {
                    recordedRequests.add(request);

                    boolean isConnections = request.url().getPath().contains(CONNECTIONS_PATH_MARKER);

                    return Mono.just(ClientResponse.create(isConnections ? connectionsStatus : HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(isConnections ? connectionsResponse : tokenResponse)
                            .build());
                })
                .build();

        XeroAuthService xeroAuthService =
                new XeroAuthService(userRequestContext, userPreferencesService, objectMapper, authWebClient);

        ReflectionTestUtils.setField(xeroAuthService, "xeroAuthUri", AUTHORIZE_URI);
        ReflectionTestUtils.setField(xeroAuthService, "clientId", CLIENT_ID);
        ReflectionTestUtils.setField(xeroAuthService, "clientSecret", CLIENT_SECRET);
        ReflectionTestUtils.setField(xeroAuthService, "xeroApiUri", API_URI);
        ReflectionTestUtils.setField(xeroAuthService, "authCallbackUri", CALLBACK_URI);

        return xeroAuthService;
    }

    @Test
    void buildsAnAuthorizeUriAgainstXerosIdentityService() {
        String authUri = xeroAuthService(TOKEN_RESPONSE, SINGLE_CONNECTION).authUri();

        assertThat(authUri).startsWith(AUTHORIZE_URI);
        assertThat(authUri).contains("response_type=code");
        assertThat(authUri).contains("client_id=" + CLIENT_ID);
    }

    /**
     * {@code offline_access} is the one that must never be dropped: without it Xero issues no
     * refresh token, and the connection dies 30 minutes later with nothing able to renew it.
     */
    @Test
    void requestsEveryScopeTheIntegrationNeeds() {
        String authUri = xeroAuthService(TOKEN_RESPONSE, SINGLE_CONNECTION).authUri();

        assertThat(authUri).contains(
                "scope=openid%20profile%20email%20accounting.transactions%20offline_access");
    }

    /**
     * {@code state} is log correlation only. The callback resolves the user from its own
     * authenticated request, so this value being forgeable does not let anyone attach a Xero
     * organisation to someone else's account.
     */
    @Test
    void carriesTheUserIdAsStateForLogCorrelation() {
        String authUri = xeroAuthService(TOKEN_RESPONSE, SINGLE_CONNECTION).authUri();

        assertThat(authUri).contains("state=" + userId);
    }

    /**
     * A token alone cannot call the Accounting API — {@code tenantId} has to travel on the
     * {@code xero-tenant-id} header of every request, and {@code GET /connections} is the only
     * place it exists.
     */
    @Test
    void resolvesTheTenantFromConnectionsAndPersistsItWithTheToken() {
        xeroAuthService(TOKEN_RESPONSE, SINGLE_CONNECTION).callback("authorization-code");

        XeroAccessToken saved = savedToken();

        assertThat(saved.accessToken()).isEqualTo("new-access-token");
        assertThat(saved.refreshToken()).isEqualTo("new-refresh-token");
        assertThat(saved.expiresIn()).isEqualTo(1800);
        assertThat(saved.tenantId()).isEqualTo("tenant-id");
        assertThat(saved.tenantName()).isEqualTo("Demo Company (Global)");
        assertThat(saved.userId()).isEqualTo(userId);
    }

    /**
     * The two Xero calls go to different hosts while sharing one client: consent and token exchange
     * live on {@code identity.xero.com}, connections on {@code api.xero.com}. The connections call
     * therefore passes an absolute URI, which {@code WebClient.uri(URI)} uses verbatim instead of
     * resolving against the client's base URL.
     * <p>
     * Asserting the whole URL rather than the path is the point: a relative {@code /connections}
     * would silently resolve against {@code identity.xero.com} and still satisfy any assertion that
     * only looked at the path.
     */
    @Test
    void resolvesTheTenantAgainstTheApiHostRatherThanTheIdentityHost() {
        xeroAuthService(TOKEN_RESPONSE, SINGLE_CONNECTION).callback("authorization-code");

        assertThat(connectionsRequest().url().toString()).isEqualTo(API_URI + "/connections");
    }

    /**
     * The connections call is made before anything is stored, so it has to carry the token the
     * exchange just returned rather than relying on a filter to supply one.
     */
    @Test
    void sendsTheFreshlyExchangedTokenOnTheConnectionsCall() {
        xeroAuthService(TOKEN_RESPONSE, SINGLE_CONNECTION).callback("authorization-code");

        assertThat(connectionsRequest().headers().getFirst(AUTHORIZATION))
                .isEqualTo("Bearer new-access-token");
    }

    /**
     * Xero's consent screen cannot be constrained to a single organisation up front, so a user may
     * come back with several. One organisation per user is the decision; the first is taken.
     */
    @Test
    void takesTheFirstTenantWhenTheUserGrantedMoreThanOneOrganisation() {
        xeroAuthService(TOKEN_RESPONSE, TWO_CONNECTIONS).callback("authorization-code");

        XeroAccessToken saved = savedToken();

        assertThat(saved.tenantId()).isEqualTo("first-tenant");
        assertThat(saved.tenantName()).isEqualTo("First Org");
    }

    /**
     * A grant that resolves to no organisation is unusable: every Accounting API call needs a
     * tenant. Storing a tenant-less token would report the user as connected and then fail every
     * call afterwards, so the connect fails here instead.
     */
    @Test
    void refusesAGrantThatResolvesToNoOrganisation() {
        XeroAuthService xeroAuthService = xeroAuthService(TOKEN_RESPONSE, "[]");

        assertThatThrownBy(() -> xeroAuthService.callback("authorization-code"))
                .isInstanceOf(XeroTokenException.class)
                .satisfies(nonRetriable(BAD_REQUEST));

        verifyNothingPersisted();
    }

    /**
     * An authorization code is single-use and short-lived; a replayed or expired one comes back as
     * {@code invalid_grant}. Retrying cannot fix it, and nothing may be persisted from it.
     */
    @Test
    void rejectsAnErrorResponseWithoutPersistingAnything() {
        String errorResponse = """
                {"error":"invalid_grant","error_description":"The authorization code is invalid."}""";

        XeroAuthService xeroAuthService = xeroAuthService(errorResponse, SINGLE_CONNECTION);

        assertThatThrownBy(() -> xeroAuthService.callback("already-used-code"))
                .isInstanceOf(XeroTokenException.class)
                .satisfies(nonRetriable(BAD_REQUEST));

        verifyNothingPersisted();
    }

    /**
     * The failure that would otherwise escape this class entirely. Jackson 3 throws an
     * <em>unchecked</em> exception, so an outage page where JSON was expected would reach
     * {@code GeneralExceptionHandler} and be rendered as {@code 200 OK} — which this endpoint's
     * caller, seeing no {@code 204}, has no way to tell apart from success.
     */
    @Test
    void treatsAnUnreadableTokenResponseAsARetriableUpstreamFailure() {
        XeroAuthService xeroAuthService = xeroAuthService(OUTAGE_PAGE, SINGLE_CONNECTION);

        assertThatThrownBy(() -> xeroAuthService.callback("authorization-code"))
                .isInstanceOf(XeroTokenException.class)
                .satisfies(retriable(SERVICE_UNAVAILABLE));

        verifyNothingPersisted();
    }

    @Test
    void treatsAnUnreadableConnectionsResponseAsARetriableUpstreamFailure() {
        XeroAuthService xeroAuthService = xeroAuthService(TOKEN_RESPONSE, OUTAGE_PAGE);

        assertThatThrownBy(() -> xeroAuthService.callback("authorization-code"))
                .isInstanceOf(XeroTokenException.class)
                .satisfies(retriable(SERVICE_UNAVAILABLE));

        verifyNothingPersisted();
    }

    /**
     * Unlike the token endpoint, a non-2xx from {@code /connections} carries nothing worth reading —
     * and the auth client has no error filter to catch it, so the status is checked before the body
     * is ever handed to Jackson.
     */
    @Test
    void treatsANonSuccessfulConnectionsLookupAsARetriableUpstreamFailure() {
        XeroAuthService xeroAuthService =
                xeroAuthService(TOKEN_RESPONSE, "{\"Detail\":\"unavailable\"}", HttpStatus.SERVICE_UNAVAILABLE);

        assertThatThrownBy(() -> xeroAuthService.callback("authorization-code"))
                .isInstanceOf(XeroTokenException.class)
                .satisfies(retriable(SERVICE_UNAVAILABLE));

        verifyNothingPersisted();
    }

    /**
     * {@code @RequestParam} makes {@code code} mandatory but not non-empty, so {@code ?code=} still
     * reaches the service. Failing here keeps a pointless round trip off Xero's token endpoint.
     */
    @Test
    void refusesACallbackCarryingNoAuthorizationCode() {
        XeroAuthService xeroAuthService = xeroAuthService(TOKEN_RESPONSE, SINGLE_CONNECTION);

        assertThatThrownBy(() -> xeroAuthService.callback("  "))
                .isInstanceOf(XeroTokenException.class)
                .satisfies(nonRetriable(BAD_REQUEST));

        assertThat(recordedRequests).isEmpty();
        verifyNothingPersisted();
    }

    /**
     * The user comes from the authenticated request, never from {@code state} — which is why a
     * forged {@code state} cannot attach an organisation to another account.
     */
    @Test
    void attributesTheTokenToTheAuthenticatedUserRatherThanToState() {
        xeroAuthService(TOKEN_RESPONSE, SINGLE_CONNECTION).callback("authorization-code");

        verify(userRequestContext).getUserId();
        verify(userPreferencesService).save(eq(userId), any(XeroAccessToken.class));
    }

    private ClientRequest connectionsRequest() {
        return recordedRequests.stream()
                .filter(request -> request.url().getPath().contains(CONNECTIONS_PATH_MARKER))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No request was made to Xero's connections endpoint"));
    }

    private XeroAccessToken savedToken() {
        ArgumentCaptor<XeroAccessToken> captor = ArgumentCaptor.forClass(XeroAccessToken.class);
        verify(userPreferencesService).save(eq(userId), captor.capture());

        return captor.getValue();
    }

    private void verifyNothingPersisted() {
        verify(userPreferencesService, never()).save(any(UUID.class), any(XeroAccessToken.class));
    }

    private static java.util.function.Consumer<Throwable> nonRetriable(HttpStatus expectedStatus) {
        return thrown -> {
            XeroTokenException xeroTokenException = (XeroTokenException) thrown;
            assertThat(xeroTokenException.getErrorCode()).isEqualTo(expectedStatus);
            assertThat(xeroTokenException.isRetriable()).isFalse();
        };
    }

    private static java.util.function.Consumer<Throwable> retriable(HttpStatus expectedStatus) {
        return thrown -> {
            XeroTokenException xeroTokenException = (XeroTokenException) thrown;
            assertThat(xeroTokenException.getErrorCode()).isEqualTo(expectedStatus);
            assertThat(xeroTokenException.isRetriable()).isTrue();
        };
    }
}
