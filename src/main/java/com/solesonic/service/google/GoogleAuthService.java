package com.solesonic.service.google;

import com.solesonic.exception.google.GoogleTokenException;
import com.solesonic.model.google.auth.GoogleAccessToken;
import com.solesonic.model.google.auth.GoogleAuthRequest;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.user.UserPreferencesService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.ZonedDateTime;
import java.util.UUID;

import static com.solesonic.config.google.GoogleConstants.GOOGLE_API_WEB_CLIENT;
import static com.solesonic.config.google.GoogleConstants.GOOGLE_AUTH_WEB_CLIENT;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class GoogleAuthService {
    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);

    public static final String AUTHORIZATION_CODE_GRANT = "authorization_code";
    public static final String REFRESH_TOKEN_GRANT = "refresh_token";

    public static final String TOKEN_PATH = "token";
    public static final String REVOKE_PATH = "revoke";

    private static final String GMAIL_PROFILE_PATH = "/gmail/v1/users/me/profile";

    private static final String CLIENT_ID_PARAM = "client_id";
    private static final String REDIRECT_URI_PARAM = "redirect_uri";
    private static final String RESPONSE_TYPE_PARAM = "response_type";
    private static final String SCOPE_PARAM = "scope";
    private static final String STATE_PARAM = "state";
    private static final String ACCESS_TYPE_PARAM = "access_type";
    private static final String PROMPT_PARAM = "prompt";
    private static final String INCLUDE_GRANTED_SCOPES_PARAM = "include_granted_scopes";
    private static final String TOKEN_PARAM = "token";

    private static final String RESPONSE_TYPE = "code";

    /**
     * Both are required to get a refresh token back. {@code access_type=offline} asks for one at
     * all; {@code prompt=consent} forces the consent screen every time, because Google returns a
     * refresh token only on a fresh grant and silently omits it otherwise.
     */
    private static final String ACCESS_TYPE = "offline";
    private static final String PROMPT = "consent";

    private static final String INCLUDE_GRANTED_SCOPES = "true";

    @Value("${google.oauth.auth-uri}")
    private String googleAuthUri;

    @Value("${google.oauth.client-id}")
    private String clientId;

    @Value("${google.oauth.client-secret}")
    private String clientSecret;

    @Value("${google.api.auth.callback.uri}")
    private String authCallbackUri;

    private final UserRequestContext userRequestContext;
    private final UserPreferencesService userPreferencesService;
    private final ObjectMapper objectMapper;

    private final WebClient authWebClient;
    private final WebClient apiWebClient;

    public GoogleAuthService(UserRequestContext userRequestContext,
                             UserPreferencesService userPreferencesService,
                             ObjectMapper objectMapper,
                             @Qualifier(GOOGLE_AUTH_WEB_CLIENT) WebClient authWebClient,
                             @Qualifier(GOOGLE_API_WEB_CLIENT) WebClient apiWebClient) {
        this.userRequestContext = userRequestContext;
        this.userPreferencesService = userPreferencesService;
        this.objectMapper = objectMapper;
        this.authWebClient = authWebClient;
        this.apiWebClient = apiWebClient;
    }

    /**
     * The URL the user visits to grant access. {@code state} carries the user id for correlation
     * in logs only — the callback resolves the user from its own authenticated request, never
     * from this value, so a forged state cannot attach someone else's Google account.
     */
    public String authUri() {
        UUID userId = userRequestContext.getUserId();
        log.info("Building Google auth URI for user: {}", userId);

        String uri = UriComponentsBuilder.fromUriString(googleAuthUri)
                .queryParam(CLIENT_ID_PARAM, clientId)
                .queryParam(REDIRECT_URI_PARAM, authCallbackUri)
                .queryParam(RESPONSE_TYPE_PARAM, RESPONSE_TYPE)
                .queryParam(SCOPE_PARAM, GoogleScope.scopeParameter())
                .queryParam(STATE_PARAM, userId)
                .queryParam(ACCESS_TYPE_PARAM, ACCESS_TYPE)
                .queryParam(PROMPT_PARAM, PROMPT)
                .queryParam(INCLUDE_GRANTED_SCOPES_PARAM, INCLUDE_GRANTED_SCOPES)
                .encode()
                .build()
                .toUriString();

        log.info("Built Google auth URI for user: {}", userId);

        return uri;
    }

    /**
     * Exchanges the authorization code for tokens and stores them.
     * <p>
     * If Google returns no refresh token, the stored one is kept. Google issues a refresh token
     * only on a fresh grant, so a re-authorization that reuses an existing grant would otherwise
     * overwrite the account's only renewable credential with null.
     */
    public void callback(String code) {
        UUID userId = userRequestContext.getUserId();
        log.info("Google callback for user: {}", userId);

        GoogleAuthRequest googleAuthRequest = GoogleAuthRequest.builder()
                .grantType(AUTHORIZATION_CODE_GRANT)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .code(code)
                .redirectUri(authCallbackUri)
                .build();

        String responseJson = authWebClient.post()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment(TOKEN_PATH)
                        .build())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(googleAuthRequest.formData()))
                .exchangeToMono(response -> response.bodyToMono(String.class))
                .block();

        GoogleAccessToken googleAccessToken = objectMapper.readValue(responseJson, GoogleAccessToken.class);

        if (StringUtils.isNotEmpty(googleAccessToken.error())) {
            log.warn("Google token exchange failed for user {}: {} - {}",
                    userId, googleAccessToken.error(), googleAccessToken.errorDescription());

            throw new GoogleTokenException(googleAccessToken.error(), BAD_REQUEST, false);
        }

        ZonedDateTime now = ZonedDateTime.now();

        GoogleAccessToken tokenWithUserInfo = GoogleAccessToken.from(googleAccessToken)
                .userId(userId)
                .refreshToken(StringUtils.defaultIfEmpty(googleAccessToken.refreshToken(), storedRefreshToken(userId)))
                .created(now)
                .updated(now)
                .build();

        log.info("Saving Google access token for user: {}", userId);
        log.info("Google token has refresh token: {}", StringUtils.isNotEmpty(tokenWithUserInfo.refreshToken()));

        userPreferencesService.save(userId, tokenWithUserInfo);
    }

    /**
     * The connected mailbox's own profile. Cheap enough to serve as a post-connect check that the
     * grant actually works — a token exchange succeeds even when the Gmail API is not enabled for
     * the project, and this is where that shows up.
     */
    public String profile() {
        String profile = apiWebClient.get()
                .uri(GMAIL_PROFILE_PATH)
                .exchangeToMono(response -> response.bodyToMono(String.class))
                .block();

        log.debug("Retrieved Gmail profile");

        return profile;
    }

    /**
     * Revokes the grant at Google and forgets the token locally. Both halves matter: dropping only
     * the local copy leaves a live grant the user cannot see, and revoking only at Google leaves a
     * stored token that fails on next use.
     */
    public void revoke() {
        UUID userId = userRequestContext.getUserId();
        log.info("Revoking Google access for user: {}", userId);

        UserPreferences userPreferences = userPreferencesService.get(userId);
        GoogleAccessToken googleAccessToken = userPreferences.getGoogleAccessToken();

        if (googleAccessToken == null) {
            log.info("No Google token stored for user: {}", userId);
            return;
        }

        String token = StringUtils.defaultIfEmpty(googleAccessToken.refreshToken(), googleAccessToken.accessToken());

        if (StringUtils.isNotEmpty(token)) {
            authWebClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .pathSegment(REVOKE_PATH)
                            .build())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(TOKEN_PARAM, token))
                    .exchangeToMono(response -> response.bodyToMono(String.class))
                    .block();
        }

        userPreferencesService.clearGoogleAccessToken(userId);

        log.info("Revoked Google access for user: {}", userId);
    }

    private String storedRefreshToken(UUID userId) {
        UserPreferences userPreferences = userPreferencesService.get(userId);
        GoogleAccessToken existing = userPreferences.getGoogleAccessToken();

        if (existing == null) {
            return null;
        }

        return existing.refreshToken();
    }
}
