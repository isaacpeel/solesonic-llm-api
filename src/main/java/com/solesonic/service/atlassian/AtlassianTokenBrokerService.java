package com.solesonic.service.atlassian;

import com.solesonic.exception.atlassian.AtlassianTokenException;
import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.atlassian.auth.AtlassianAuthRequest;
import com.solesonic.model.atlassian.broker.TokenExchange;
import com.solesonic.model.atlassian.broker.TokenResponse;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.service.user.UserPreferencesService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.time.ZonedDateTime;
import java.util.UUID;

import static com.solesonic.service.atlassian.JiraAuthService.OAUTH_PATH;
import static com.solesonic.service.atlassian.JiraAuthService.TOKEN_PATH;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
public class AtlassianTokenBrokerService {

    private static final Logger log = LoggerFactory.getLogger(AtlassianTokenBrokerService.class);
    private static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";

    private final String atlassianTokenUri;
    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper objectMapper;
    private final UserPreferencesService userPreferencesService;

    public AtlassianTokenBrokerService(@Value("${atlassian.oauth.token-uri:https://auth.atlassian.com/oauth/token}") String atlassianTokenUri,
                                       @Value("${atlassian.oauth.client-id}") String clientId,
                                       @Value("${atlassian.oauth.client-secret}") String clientSecret, ObjectMapper objectMapper, UserPreferencesService userPreferencesService) {
        this.atlassianTokenUri = atlassianTokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.objectMapper = objectMapper;
        this.userPreferencesService = userPreferencesService;
    }

    public TokenResponse mintToken(TokenExchange tokenExchange) {
        UUID userId = tokenExchange.subjectToken();
        String siteId = tokenExchange.audience();

        log.info("Minting token for user {} siteId {}", userId, siteId);

        UserPreferences userPreferences = userPreferencesService.get(userId);
        AtlassianAccessToken atlassianAccessToken = userPreferences.getAtlassianAccessToken();

        if (atlassianAccessToken == null) {
            log.warn("No refresh token found for user {} - RECONNECT_REQUIRED", userId);
            throw new AtlassianTokenException("No refresh token found for user " + userId, BAD_REQUEST, false);
        }

        if(atlassianAccessToken.isExpired()) {
            String refreshToken = atlassianAccessToken.refreshToken();
            atlassianAccessToken = refreshAtlassianToken(refreshToken);
            userPreferencesService.update(userId, atlassianAccessToken);
        }

        ZonedDateTime issuedAt = ZonedDateTime.now();

        return new TokenResponse(
                atlassianAccessToken.accessToken(),
                atlassianAccessToken.expiresIn(),
                issuedAt,
                userId);
    }

    private AtlassianAccessToken refreshAtlassianToken(String refreshToken) {
        AtlassianAuthRequest atlassianAuthRequest = AtlassianAuthRequest.builder()
                .grantType(GRANT_TYPE_REFRESH_TOKEN)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .refreshToken(refreshToken)
                .build();

        String authRequest = objectMapper.writeValueAsString(atlassianAuthRequest);
        assert authRequest != null;

        WebClient client = WebClient.create(atlassianTokenUri);

        String responseJson = client.post()
                .uri(uriBuilder ->
                        uriBuilder
                                .pathSegment(OAUTH_PATH)
                                .pathSegment(TOKEN_PATH)
                                .build()
                )
                .bodyValue(atlassianAuthRequest)
                .exchangeToMono(response -> response.bodyToMono(String.class))
                .block();

        AtlassianAccessToken atlassianAccessToken = objectMapper.readValue(responseJson, AtlassianAccessToken.class);

        if (StringUtils.isNotEmpty(atlassianAccessToken.error())) {
            String error = atlassianAccessToken.error();
            String errorDescription = atlassianAccessToken.errorDescription();
            String exceptionMessage = error + ": " + errorDescription;

            throw new AtlassianTokenException(exceptionMessage, SERVICE_UNAVAILABLE, true);
        }

        log.info("Token refresh successful");

        return atlassianAccessToken;

    }
}