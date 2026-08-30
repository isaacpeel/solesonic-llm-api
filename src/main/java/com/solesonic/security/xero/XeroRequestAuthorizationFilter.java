package com.solesonic.security.xero;

import com.solesonic.exception.xero.XeroTokenException;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.model.xero.auth.XeroAccessToken;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.user.UserPreferencesService;
import com.solesonic.service.xero.XeroTokenRefreshService;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Puts the calling user's Xero credentials on every Accounting API request, refreshing the token
 * first if it has expired.
 * <p>
 * Two headers, not one. Google's equivalent needs only {@code Authorization}; a Xero token carries
 * no indication of which organisation it acts on, so {@code xero-tenant-id} has to travel beside it
 * or the call is rejected.
 */
@Component
public class XeroRequestAuthorizationFilter implements ExchangeFilterFunction {
    private static final Logger log = LoggerFactory.getLogger(XeroRequestAuthorizationFilter.class);

    private static final String BEARER = "Bearer ";
    private static final String XERO_TENANT_ID = "xero-tenant-id";

    private static final String NOT_CONNECTED_ERROR = "xero_not_connected";

    private final UserRequestContext userRequestContext;
    private final UserPreferencesService userPreferencesService;
    private final XeroTokenRefreshService xeroTokenRefreshService;

    public XeroRequestAuthorizationFilter(UserRequestContext userRequestContext,
                                          UserPreferencesService userPreferencesService,
                                          XeroTokenRefreshService xeroTokenRefreshService) {
        this.userRequestContext = userRequestContext;
        this.userPreferencesService = userPreferencesService;
        this.xeroTokenRefreshService = xeroTokenRefreshService;
    }

    @Override
    @Nonnull
    public Mono<ClientResponse> filter(@Nonnull ClientRequest request, @Nonnull ExchangeFunction next) {
        log.info("Filtering {}: {}", request.method().name(), request.url());

        XeroAccessToken xeroAccessToken = xeroAccessToken();

        ClientRequest modifiedRequest = ClientRequest.from(request)
                .header(AUTHORIZATION, BEARER + xeroAccessToken.accessToken())
                .header(XERO_TENANT_ID, xeroAccessToken.tenantId())
                .build();

        return next.exchange(modifiedRequest);
    }

    /**
     * The stored connection for the request-scoped user, renewed if it has expired.
     * <p>
     * A user who has never connected Xero has no token at all, and that is refused here rather than
     * dereferenced: the Google equivalent turns this case into a {@link NullPointerException}, which
     * {@code GeneralExceptionHandler} would render as {@code 200 OK}. "Reconnect required" is both
     * true and actionable.
     * <p>
     * A live token is reused rather than refreshed on principle — Xero rotates the refresh token on
     * every use and invalidates the previous one, so a needless refresh spends the credential.
     */
    public XeroAccessToken xeroAccessToken() {
        UUID userId = userRequestContext.getUserId();
        UserPreferences userPreferences = userPreferencesService.get(userId);
        XeroAccessToken xeroAccessToken = userPreferences.getXeroAccessToken();

        if (xeroAccessToken == null) {
            log.warn("No Xero connection stored for user: {}", userId);

            throw new XeroTokenException(NOT_CONNECTED_ERROR, BAD_REQUEST, false);
        }

        if (!xeroAccessToken.isExpired()) {
            log.info("Reusing non expired Xero access token for user: {}", userId);

            return xeroAccessToken;
        }

        XeroAccessToken refreshedToken = xeroTokenRefreshService.refresh(xeroAccessToken);

        log.info("Updating Xero access token for user: {}", userId);

        userPreferencesService.update(userId, refreshedToken);

        return refreshedToken;
    }
}
