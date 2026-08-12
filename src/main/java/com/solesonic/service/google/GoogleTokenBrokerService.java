package com.solesonic.service.google;

import com.solesonic.exception.google.GoogleTokenException;
import com.solesonic.model.google.auth.GoogleAccessToken;
import com.solesonic.model.google.broker.GoogleTokenExchange;
import com.solesonic.model.google.broker.GoogleTokenResponse;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.service.user.UserPreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * Hands a short-lived Google access token to an MCP server acting on a user's behalf. The refresh
 * token never leaves this application.
 */
@Service
public class GoogleTokenBrokerService {
    private static final Logger log = LoggerFactory.getLogger(GoogleTokenBrokerService.class);

    private final UserPreferencesService userPreferencesService;
    private final GoogleTokenRefreshService googleTokenRefreshService;

    public GoogleTokenBrokerService(UserPreferencesService userPreferencesService,
                                    GoogleTokenRefreshService googleTokenRefreshService) {
        this.userPreferencesService = userPreferencesService;
        this.googleTokenRefreshService = googleTokenRefreshService;
    }

    public GoogleTokenResponse mintToken(GoogleTokenExchange googleTokenExchange) {
        UUID userId = googleTokenExchange.subjectToken();

        log.info("Minting Google token for user {}", userId);

        UserPreferences userPreferences = userPreferencesService.get(userId);
        GoogleAccessToken googleAccessToken = userPreferences.getGoogleAccessToken();

        if (googleAccessToken == null) {
            log.warn("No Google refresh token found for user {} - RECONNECT_REQUIRED", userId);
            throw new GoogleTokenException("No Google refresh token found for user " + userId, BAD_REQUEST, false);
        }

        if (googleAccessToken.isExpired()) {
            googleAccessToken = googleTokenRefreshService.refresh(googleAccessToken);
            userPreferencesService.update(userId, googleAccessToken);
        }

        Integer expiresIn = googleAccessToken.expiresIn();

        if (expiresIn == null) {
            log.warn("Google token for user {} carries no expiry", userId);
            throw new GoogleTokenException("Google token carries no expiry", SERVICE_UNAVAILABLE, true);
        }

        return new GoogleTokenResponse(
                googleAccessToken.accessToken(),
                expiresIn,
                ZonedDateTime.now(),
                userId);
    }
}
