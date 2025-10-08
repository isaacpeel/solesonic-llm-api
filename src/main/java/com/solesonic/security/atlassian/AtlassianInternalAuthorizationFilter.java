package com.solesonic.security.atlassian;

import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.atlassian.auth.AtlassianAuthRequest;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.service.user.UserPreferencesService;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;

import static com.solesonic.service.atlassian.JiraAuthService.*;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

@Component
public class AtlassianInternalAuthorizationFilter implements ExchangeFilterFunction {
    private static final Logger log = LoggerFactory.getLogger(AtlassianInternalAuthorizationFilter.class);
    private final UserPreferencesService userPreferencesService;

    @Value("${atlassian.oauth.token-uri}")
    private String atlassianAuthUri;

    @Value("${atlassian.oauth.client-id}")
    private String authClientId;

    @Value("${atlassian.oauth.client-secret}")
    private String authClientSecret;

    public AtlassianInternalAuthorizationFilter(UserPreferencesService userPreferencesService) {
        this.userPreferencesService = userPreferencesService;
    }

    @Override
    @Nonnull
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        log.debug("Filtering request to: {}", request.url());

        AtlassianAccessToken atlassianAccessToken = atlassianAccessToken();
        String accessToken = atlassianAccessToken.accessToken();

        ClientRequest modifiedRequest = ClientRequest.from(request)
                .header(AUTHORIZATION, "Bearer " + accessToken)
                .build();

        return next.exchange(modifiedRequest);
    }

    public AtlassianAccessToken atlassianAccessToken() {
        UserPreferences serviceAccountPreferences = userPreferencesService.serviceAccount();
        AtlassianAccessToken serviceAccountToken = serviceAccountPreferences.getAtlassianAccessToken();

        if(!serviceAccountToken.isExpired()) {
            log.debug("Reusing non expired access token for admin user.");
            return serviceAccountToken;
        }

        AtlassianAccessToken refreshedToken = refreshToken(serviceAccountToken, authClientId, authClientSecret, atlassianAuthUri);

        log.debug("Updating access token for admin user.");
        log.debug("Admin token expiresIn: {}", refreshedToken.expiresIn());

        serviceAccountPreferences.setAtlassianAccessToken(refreshedToken);
        userPreferencesService.save(serviceAccountPreferences.getUserId(),  serviceAccountPreferences);

        return refreshedToken;
    }

    static AtlassianAccessToken refreshToken(AtlassianAccessToken adminUserToken, String authClientId, String authClientSecret, String atlassianAuthUri) {
        String jiraRefreshToken = adminUserToken.refreshToken();

        AtlassianAuthRequest atlassianAuthRequest = AtlassianAuthRequest.builder()
                .grantType(REFRESH_TOKEN)
                .clientId(authClientId)
                .clientSecret(authClientSecret)
                .refreshToken(jiraRefreshToken)
                .build();

        WebClient client = WebClient.create(atlassianAuthUri);

        AtlassianAccessToken newAccessToken = client.post()
                .uri(uriBuilder ->
                        uriBuilder
                                .pathSegment(OAUTH_PATH)
                                .pathSegment(TOKEN_PATH)
                                .build()
                )
                .bodyValue(atlassianAuthRequest)
                .exchangeToMono(response -> response.bodyToMono(AtlassianAccessToken.class))
                .block();

        assert newAccessToken != null;

        return AtlassianAccessToken.from(newAccessToken)
                .updated(ZonedDateTime.now())
                .created(ZonedDateTime.now())
                .build();
    }
}
