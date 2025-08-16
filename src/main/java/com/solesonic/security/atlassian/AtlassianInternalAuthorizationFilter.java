package com.solesonic.security.atlassian;

import com.solesonic.exception.JiraException;
import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.atlassian.auth.AtlassianAuthRequest;
import com.solesonic.repository.atlassian.AtlassianAccessTokenRepository;
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
    private final AtlassianAccessTokenRepository atlassianAccessTokenRepository;

    @Value("${jira.api.auth.uri}")
    private String atlassianAuthUri;

    @Value("${jira.api.client.id}")
    private String authClientId;

    @Value("${jira.api.client.secret}")
    private String authClientSecret;

    public AtlassianInternalAuthorizationFilter(AtlassianAccessTokenRepository atlassianAccessTokenRepository) {
        this.atlassianAccessTokenRepository = atlassianAccessTokenRepository;
    }

    @Override
    @Nonnull
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        log.debug("Filtering request to: {}", request.url());

        AtlassianAccessToken atlassianAccessToken = atlassianAccessToken();
        String accessToken = atlassianAccessToken.getAccessToken();

        ClientRequest modifiedRequest = ClientRequest.from(request)
                .header(AUTHORIZATION, "Bearer " + accessToken)
                .build();

        return next.exchange(modifiedRequest);
    }

    public AtlassianAccessToken atlassianAccessToken() {
        AtlassianAccessToken adminUserToken = atlassianAccessTokenRepository.findAdminUser()
                .orElseThrow(() -> new JiraException("Can't find access admin token."));

        if(adminUserToken.isExpired()) {
            log.debug("Reusing non expired access token for admin user.");
            return adminUserToken;
        }

        refreshToken(adminUserToken, authClientId, authClientSecret, atlassianAuthUri);

        log.debug("Updating access token for admin user.");
        log.debug("Admin token expiresIn: {}", adminUserToken.getExpiresIn());

        return atlassianAccessTokenRepository.saveAndFlush(adminUserToken);
    }

    static void refreshToken(AtlassianAccessToken adminUserToken, String authClientId, String authClientSecret, String atlassianAuthUri) {
        String jiraRefreshToken = adminUserToken.getRefreshToken();

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
        adminUserToken.setAccessToken(newAccessToken.getAccessToken());
        adminUserToken.setRefreshToken(newAccessToken.getRefreshToken());
        adminUserToken.setUpdated(ZonedDateTime.now());
        adminUserToken.setExpiresIn(newAccessToken.getExpiresIn());
    }
}
