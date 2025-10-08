package com.solesonic.security.atlassian;

import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.user.UserPreferencesService;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

@Component
public class AtlassianRequestAuthorizationFilter implements ExchangeFilterFunction {
    private static final Logger log = LoggerFactory.getLogger(AtlassianRequestAuthorizationFilter.class);
    private final UserRequestContext userRequestContext;
    private final UserPreferencesService userPreferencesService;

    @Value("${atlassian.oauth.token-uri}")
    private String atlassianAuthUri;

    @Value("${atlassian.oauth.client-id}")
    private String authClientId;

    @Value("${atlassian.oauth.client-secret}")
    private String authClientSecret;

    public AtlassianRequestAuthorizationFilter(UserRequestContext userRequestContext,
                                               UserPreferencesService userPreferencesService) {
        this.userRequestContext = userRequestContext;
        this.userPreferencesService = userPreferencesService;
    }

    @Override
    @Nonnull
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        log.info("Filtering {}: {}", request.method().name(), request.url());

        AtlassianAccessToken atlassianAccessToken = atlassianAccessToken();
        String accessToken = atlassianAccessToken.accessToken();

        ClientRequest modifiedRequest = ClientRequest.from(request)
                .header(AUTHORIZATION, "Bearer " + accessToken)
                .build();

        return next.exchange(modifiedRequest);
    }

    public AtlassianAccessToken atlassianAccessToken() {
        UUID userId = userRequestContext.getUserId();
        UserPreferences userPreferences = userPreferencesService.get(userId);
        AtlassianAccessToken atlassianAccessToken = userPreferences.getAtlassianAccessToken();


        if(!atlassianAccessToken.isExpired()) {
            log.info("Reusing non expired access token for user: {}", userId);
            return atlassianAccessToken;
        }

        AtlassianAccessToken refreshedToken = AtlassianInternalAuthorizationFilter.refreshToken(atlassianAccessToken, authClientId, authClientSecret, atlassianAuthUri);

        log.info("Updating access token for user: {}", userId);
        log.info("Token has expiresIn: {}", refreshedToken.expiresIn() != null);

        userPreferences.setAtlassianAccessToken(refreshedToken);
        userPreferencesService.save(userId, userPreferences);

        return refreshedToken;
    }
}
