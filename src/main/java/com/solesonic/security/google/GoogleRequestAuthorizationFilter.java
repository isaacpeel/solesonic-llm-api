package com.solesonic.security.google;

import com.solesonic.model.google.auth.GoogleAccessToken;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.google.GoogleTokenRefreshService;
import com.solesonic.service.user.UserPreferencesService;
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

/**
 * Puts the calling user's Google access token on every Gmail request, refreshing it first if it
 * has expired.
 */
@Component
public class GoogleRequestAuthorizationFilter implements ExchangeFilterFunction {
    private static final Logger log = LoggerFactory.getLogger(GoogleRequestAuthorizationFilter.class);
    private static final String BEARER = "Bearer ";

    private final UserRequestContext userRequestContext;
    private final UserPreferencesService userPreferencesService;
    private final GoogleTokenRefreshService googleTokenRefreshService;

    public GoogleRequestAuthorizationFilter(UserRequestContext userRequestContext,
                                            UserPreferencesService userPreferencesService,
                                            GoogleTokenRefreshService googleTokenRefreshService) {
        this.userRequestContext = userRequestContext;
        this.userPreferencesService = userPreferencesService;
        this.googleTokenRefreshService = googleTokenRefreshService;
    }

    @Override
    @Nonnull
    public Mono<ClientResponse> filter(@Nonnull ClientRequest request, @Nonnull ExchangeFunction next) {
        log.info("Filtering {}: {}", request.method().name(), request.url());

        GoogleAccessToken googleAccessToken = googleAccessToken();
        String accessToken = googleAccessToken.accessToken();

        ClientRequest modifiedRequest = ClientRequest.from(request)
                .header(AUTHORIZATION, BEARER + accessToken)
                .build();

        return next.exchange(modifiedRequest);
    }

    public GoogleAccessToken googleAccessToken() {
        UUID userId = userRequestContext.getUserId();
        UserPreferences userPreferences = userPreferencesService.get(userId);
        GoogleAccessToken googleAccessToken = userPreferences.getGoogleAccessToken();

        if (!googleAccessToken.isExpired()) {
            log.info("Reusing non expired Google access token for user: {}", userId);
            return googleAccessToken;
        }

        GoogleAccessToken refreshedToken = googleTokenRefreshService.refresh(googleAccessToken);

        log.info("Updating Google access token for user: {}", userId);
        log.info("Token has expiresIn: {}", refreshedToken.expiresIn() != null);

        userPreferences.setGoogleAccessToken(refreshedToken);
        userPreferencesService.save(userId, userPreferences);

        return refreshedToken;
    }
}
