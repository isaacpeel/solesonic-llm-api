package com.solesonic.service.google;

import com.solesonic.exception.google.GoogleTokenException;
import com.solesonic.model.google.auth.GoogleAccessToken;
import com.solesonic.model.google.auth.GoogleAuthRequest;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.time.ZonedDateTime;

import static com.solesonic.config.google.GoogleConstants.GOOGLE_AUTH_WEB_CLIENT;
import static com.solesonic.service.google.GoogleAuthService.REFRESH_TOKEN_GRANT;
import static com.solesonic.service.google.GoogleAuthService.TOKEN_PATH;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * The one place a Google token is refreshed, shared by the per-request filter and the token
 * broker. The Atlassian equivalent grew three near-identical copies with three different ways of
 * building a client; there is one here on purpose.
 */
@Service
public class GoogleTokenRefreshService {
    private static final Logger log = LoggerFactory.getLogger(GoogleTokenRefreshService.class);

    /**
     * Google's answer when a refresh token has been revoked, expired, or was issued to a consent
     * screen still in Testing mode (those expire after seven days). No amount of retrying fixes
     * it — the user has to consent again.
     */
    private static final String INVALID_GRANT = "invalid_grant";

    @Value("${google.oauth.client-id}")
    private String clientId;

    @Value("${google.oauth.client-secret}")
    private String clientSecret;

    private final WebClient authWebClient;
    private final ObjectMapper objectMapper;

    public GoogleTokenRefreshService(@Qualifier(GOOGLE_AUTH_WEB_CLIENT) WebClient authWebClient,
                                     ObjectMapper objectMapper) {
        this.authWebClient = authWebClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Exchanges the stored refresh token for a new access token.
     * <p>
     * The returned token always carries a refresh token: Google omits one from a refresh response,
     * so the existing one is carried forward. Persisting the response as-is would blank the only
     * credential that can renew this account.
     */
    public GoogleAccessToken refresh(GoogleAccessToken googleAccessToken) {
        String refreshToken = googleAccessToken.refreshToken();

        if (StringUtils.isEmpty(refreshToken)) {
            throw new GoogleTokenException("No Google refresh token stored", BAD_REQUEST, false);
        }

        GoogleAuthRequest googleAuthRequest = GoogleAuthRequest.builder()
                .grantType(REFRESH_TOKEN_GRANT)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .refreshToken(refreshToken)
                .build();

        String responseJson = authWebClient.post()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment(TOKEN_PATH)
                        .build())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(googleAuthRequest.formData()))
                .exchangeToMono(response -> response.bodyToMono(String.class))
                .block();

        GoogleAccessToken refreshed = objectMapper.readValue(responseJson, GoogleAccessToken.class);

        if (StringUtils.isNotEmpty(refreshed.error())) {
            throw tokenException(refreshed.error(), refreshed.errorDescription());
        }

        log.debug("Google token refresh successful");

        ZonedDateTime now = ZonedDateTime.now();

        return GoogleAccessToken.from(refreshed)
                .userId(googleAccessToken.userId())
                .refreshToken(StringUtils.defaultIfEmpty(refreshed.refreshToken(), refreshToken))
                .created(now)
                .updated(now)
                .build();
    }

    /**
     * Keeps Google's own wording out of the response. The error code decides whether the caller is
     * told to reconnect or to retry; the description is logged and dropped.
     */
    private static GoogleTokenException tokenException(String error, String errorDescription) {
        log.warn("Google token refresh failed: {} - {}", error, errorDescription);

        if (INVALID_GRANT.equals(error)) {
            return new GoogleTokenException(error, BAD_REQUEST, false);
        }

        return new GoogleTokenException(error, SERVICE_UNAVAILABLE, true);
    }
}
