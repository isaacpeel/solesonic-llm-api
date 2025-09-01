package com.solesonic.security.atlassian;

import com.solesonic.exception.JiraException;
import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.service.atlassian.AtlassianTokenStore;
import com.solesonic.scope.UserRequestContext;
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
    private final AtlassianTokenStore atlassianTokenStore;

    @Value("${jira.api.auth.uri}")
    private String atlassianAuthUri;

    @Value("${jira.api.client.id}")
    private String authClientId;

    @Value("${jira.api.client.secret}")
    private String authClientSecret;

    public AtlassianRequestAuthorizationFilter(UserRequestContext userRequestContext,
                                               AtlassianTokenStore atlassianTokenStore) {
        this.userRequestContext = userRequestContext;
        this.atlassianTokenStore = atlassianTokenStore;
    }

    @Override
    @Nonnull
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        log.info("Filtering {}: {}", request.method().name(), request.url());

        AtlassianAccessToken atlassianAccessToken = atlassianAccessToken();
        String accessToken = atlassianAccessToken.getAccessToken();

        ClientRequest modifiedRequest = ClientRequest.from(request)
                .header(AUTHORIZATION, "Bearer " + accessToken)
                .build();

        return next.exchange(modifiedRequest);
    }

    public AtlassianAccessToken atlassianAccessToken() {
        UUID userId = userRequestContext.getUserId();
        AtlassianAccessToken atlassianAccessToken = atlassianTokenStore.load(userId)
                .orElseThrow(() -> new JiraException("Can't find access token."));

        if(!atlassianAccessToken.isExpired()) {
            log.info("Reusing non expired access token for user: {}", userId);
            return atlassianAccessToken;
        }

        AtlassianInternalAuthorizationFilter.refreshToken(atlassianAccessToken, authClientId, authClientSecret, atlassianAuthUri);

        log.info("Updating access token for user: {}", userId);
        log.info("Token has expiresIn: {}", atlassianAccessToken.getExpiresIn() != null);

        atlassianTokenStore.save(atlassianAccessToken);
        return atlassianAccessToken;
    }
}
