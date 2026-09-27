package com.solesonic.security.xero;

import com.solesonic.exception.xero.XeroApiException;
import com.solesonic.exception.xero.XeroTokenException;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.model.xero.auth.XeroAccessToken;
import com.solesonic.service.user.UserPreferencesService;
import com.solesonic.service.xero.XeroTokenRefreshService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.solesonic.config.xero.XeroConstants.XERO_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpMethod.POST;

/**
 * What every outgoing Xero Accounting API call carries.
 * <p>
 * The header pair is the point: unlike Google's equivalent filter, an {@code Authorization} header
 * alone is not enough for Xero — a token with no {@code xero-tenant-id} beside it is rejected by
 * every endpoint on the Accounting API.
 * <p>
 * The second point is who the call is made <em>as</em>. This filter takes the user from the Reactor
 * subscription context, never from the request scope, and
 * {@link #resolvesTheUserOffTheRequestThread()} is the case that matters: a chat tool runs on a
 * {@code boundedElastic} worker with no HTTP request bound to it, so a request-scoped lookup here
 * would fail every invoice created from a conversation while passing every test made from a
 * controller thread.
 */
class XeroRequestAuthorizationFilterTest {

    private static final String TENANT_ID_HEADER = "xero-tenant-id";
    private static final URI INVOICES_URI = URI.create("https://api.xero.com/api.xro/2.0/Invoices");

    private final List<ClientRequest> forwardedRequests = new ArrayList<>();

    private UUID userId;
    private UserPreferencesService userPreferencesService;
    private XeroTokenRefreshService xeroTokenRefreshService;
    private XeroRequestAuthorizationFilter xeroRequestAuthorizationFilter;

    @BeforeEach
    void beforeEach() {
        userId = UUID.randomUUID();
        userPreferencesService = mock(UserPreferencesService.class);
        xeroTokenRefreshService = mock(XeroTokenRefreshService.class);

        xeroRequestAuthorizationFilter = new XeroRequestAuthorizationFilter(
                userPreferencesService, xeroTokenRefreshService);
    }

    private void storedToken(XeroAccessToken xeroAccessToken) {
        UserPreferences userPreferences = new UserPreferences();
        userPreferences.setUserId(userId);
        userPreferences.setXeroAccessToken(xeroAccessToken);

        when(userPreferencesService.get(userId)).thenReturn(userPreferences);
    }

    private XeroAccessToken token(String accessToken, ZonedDateTime created) {
        return XeroAccessToken.builder()
                .userId(userId)
                .accessToken(accessToken)
                .refreshToken("refresh-token")
                .tokenType("Bearer")
                .expiresIn(1800)
                .tenantId("tenant-id")
                .tenantName("Demo Company (Global)")
                .created(created)
                .updated(created)
                .build();
    }

    private XeroAccessToken liveToken() {
        return token("live-access-token", ZonedDateTime.now());
    }

    private XeroAccessToken expiredToken() {
        return token("expired-access-token", ZonedDateTime.now().minusHours(2));
    }

    private void filter() {
        filtered().contextWrite(context -> context.put(XERO_USER_ID, userId)).block();
    }

    private Mono<ClientResponse> filtered() {
        ClientRequest request = ClientRequest.create(POST, INVOICES_URI).build();

        ExchangeFunction next = forwarded -> {
            forwardedRequests.add(forwarded);

            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };

        return xeroRequestAuthorizationFilter.filter(request, next);
    }

    private ClientRequest forwardedRequest() {
        assertThat(forwardedRequests).hasSize(1);

        return forwardedRequests.getFirst();
    }

    /**
     * Both headers, on every call. Xero's Accounting API rejects a request carrying a valid bearer
     * token but no tenant, so the two are only ever useful together.
     */
    @Test
    void addsBothTheBearerTokenAndTheTenantHeader() {
        storedToken(liveToken());

        filter();

        assertThat(forwardedRequest().headers().getFirst(AUTHORIZATION))
                .isEqualTo("Bearer live-access-token");
        assertThat(forwardedRequest().headers().getFirst(TENANT_ID_HEADER))
                .isEqualTo("tenant-id");
    }

    /**
     * Every refresh burns the stored refresh token, because Xero rotates it and invalidates the
     * previous one. Refreshing a token that has not expired is therefore not merely wasteful.
     */
    @Test
    void doesNotRefreshATokenThatIsStillValid() {
        storedToken(liveToken());

        filter();

        verify(xeroTokenRefreshService, never()).refresh(any(XeroAccessToken.class));
        verify(userPreferencesService, never()).update(any(UUID.class), any(XeroAccessToken.class));
    }

    @Test
    void refreshesAndPersistsAnExpiredToken() {
        XeroAccessToken expired = expiredToken();
        XeroAccessToken refreshed = liveToken();

        storedToken(expired);
        when(xeroTokenRefreshService.refresh(expired)).thenReturn(refreshed);

        filter();

        verify(xeroTokenRefreshService).refresh(expired);
        verify(userPreferencesService).update(userId, refreshed);
    }

    /**
     * The refreshed token has to reach Xero on this very call. Persisting it and then sending the
     * expired one would fail the request that triggered the refresh.
     */
    @Test
    void sendsTheRefreshedTokenRatherThanTheExpiredOne() {
        XeroAccessToken expired = expiredToken();

        storedToken(expired);
        when(xeroTokenRefreshService.refresh(expired)).thenReturn(liveToken());

        filter();

        assertThat(forwardedRequest().headers().getFirst(AUTHORIZATION))
                .isEqualTo("Bearer live-access-token");
    }

    /**
     * A user who never connected Xero has no token at all. Google's equivalent filter dereferences
     * this straight into a {@code NullPointerException}, which {@code GeneralExceptionHandler}
     * renders as {@code 200 OK}; "reconnect required" is both true and actionable.
     */
    @Test
    void refusesToCallXeroForAUserWhoHasNotConnected() {
        storedToken(null);

        assertThatThrownBy(this::filter)
                .isInstanceOf(XeroTokenException.class)
                .satisfies(nonRetriable());

        assertThat(forwardedRequests).isEmpty();
    }

    /**
     * The case this filter's whole shape exists for. A Reactor context travels with the subscription
     * rather than the thread, so the user survives the hop onto the scheduler the chat tool path runs
     * on — where {@code RequestContextHolder} holds nothing at all.
     */
    @Test
    void resolvesTheUserOffTheRequestThread() {
        storedToken(liveToken());

        filtered()
                .contextWrite(context -> context.put(XERO_USER_ID, userId))
                .subscribeOn(Schedulers.boundedElastic())
                .block();

        assertThat(forwardedRequest().headers().getFirst(AUTHORIZATION))
                .isEqualTo("Bearer live-access-token");
        assertThat(forwardedRequest().headers().getFirst(TENANT_ID_HEADER))
                .isEqualTo("tenant-id");
    }

    /**
     * A call assembled without a user is a programming error, not a user condition, and it is refused
     * before anything reaches Xero. It is deliberately not a {@link XeroTokenException}: telling
     * someone to reconnect an account they are already connected to would send them to fix the one
     * thing that is not broken.
     */
    @Test
    void refusesACallThatCarriesNoUser() {
        storedToken(liveToken());

        assertThatThrownBy(() -> filtered().block())
                .isInstanceOf(XeroApiException.class);

        assertThat(forwardedRequests).isEmpty();
    }

    private static Consumer<Throwable> nonRetriable() {
        return thrown -> {
            XeroTokenException xeroTokenException = (XeroTokenException) thrown;
            assertThat(xeroTokenException.getErrorCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(xeroTokenException.isRetriable()).isFalse();
        };
    }
}
