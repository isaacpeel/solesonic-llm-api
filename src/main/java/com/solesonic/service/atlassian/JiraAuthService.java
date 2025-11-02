package com.solesonic.service.atlassian;

import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.atlassian.auth.AtlassianAuthRequest;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.user.UserPreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.time.ZonedDateTime;
import java.util.UUID;

import static com.solesonic.config.atlassian.AtlassianConstants.ATLASSIAN_API_WEB_CLIENT;
import static com.solesonic.config.atlassian.AtlassianConstants.ATLASSIAN_AUTH_WEB_CLIENT;
import static com.solesonic.service.atlassian.AtlassianScope.urlEncodedScopes;
import static java.nio.charset.StandardCharsets.UTF_8;

@Service
public class JiraAuthService {
    private static final Logger log = LoggerFactory.getLogger(JiraAuthService.class);
    public static final String AUTHORIZATION_CODE = "authorization_code";
    public static final String AUDIENCE = "api.atlassian.com";
    public static final String REFRESH_TOKEN = "refresh_token";

    public static final String RESPONSE_TYPE = "code";
    public static final String PROMPT = "consent";
    public static final String OAUTH_PATH = "oauth";
    public static final String TOKEN_PATH = "token";
    public static final String ACCESSIBLE_RESOURCES_PATH = "accessible-resource";
    public static final String AUTHORIZE_PATH = "authorize";
    public static final String AUDIENCE_PARAM = "audience";
    public static final String CLIENT_ID_PARAM = "client_id";
    public static final String SCOPE_PARAM = "scope";
    public static final String REDIRECT_URI_PARAM = "redirect_uri";
    public static final String STATE_PARAM = "state";
    public static final String RESPONSE_TYPE_PARAM = "response_type";
    public static final String PROMPT_PARAM = "prompt";

    @Value("${atlassian.oauth.token-uri}")
    private String jiraAuthUri;

    @Value("${jira.api.auth.callback.uri}")
    private String authCallbackUri;

    @Value("${atlassian.oauth.client-id}")
    private String authClientId;

    @Value("${atlassian.oauth.client-secret}")
    private String authClientSecret;

    @Value("${atlassian.oauth.client-id}")
    private String clientId;

    private final UserRequestContext userRequestContext;
    private final UserPreferencesService userPreferencesService;

    private final WebClient authWebClient;
    private final WebClient apiWebClient;

    public JiraAuthService(UserRequestContext userRequestContext, UserPreferencesService userPreferencesService,
                           @Qualifier(ATLASSIAN_AUTH_WEB_CLIENT) WebClient authWebClient,
                           @Qualifier(ATLASSIAN_API_WEB_CLIENT) WebClient apiWebClient) {
        this.userRequestContext = userRequestContext;
        this.userPreferencesService = userPreferencesService;
        this.authWebClient = authWebClient;
        this.apiWebClient = apiWebClient;
    }

    public String authUri() {
        UUID userId = userRequestContext.getUserId();
        log.info("Building auth URI for user: {}", userId);

        String[] scopes = urlEncodedScopes();

        String jiraAuthUri = UriComponentsBuilder.fromUriString(this.jiraAuthUri)
                .pathSegment(AUTHORIZE_PATH)
                .queryParam(AUDIENCE_PARAM, AUDIENCE)
                .queryParam(CLIENT_ID_PARAM, clientId)
                .queryParam(SCOPE_PARAM, String.join(" ", scopes))
                .queryParam(REDIRECT_URI_PARAM, URLEncoder.encode(authCallbackUri, UTF_8))
                .queryParam(STATE_PARAM, userId)
                .queryParam(RESPONSE_TYPE_PARAM, RESPONSE_TYPE)
                .queryParam(PROMPT_PARAM, PROMPT)
                .build()
                .toUriString();

        log.info("Jira auth URI: {}", jiraAuthUri);
        return jiraAuthUri;
    }

    public void callback(String code) {
        UUID userId = userRequestContext.getUserId();
        log.info("Callback for user: {}", userId);

        AtlassianAuthRequest atlassianAuthRequest = AtlassianAuthRequest.builder()
                .grantType(AUTHORIZATION_CODE)
                .clientId(authClientId)
                .clientSecret(authClientSecret)
                .code(code)
                .redirectUri(authCallbackUri)
                .build();

        AtlassianAccessToken atlassianAccessToken = authWebClient.post()
                .uri(uriBuilder ->
                        uriBuilder
                                .pathSegment(OAUTH_PATH)
                                .pathSegment(TOKEN_PATH)
                                .build()
                )
                .bodyValue(atlassianAuthRequest)
                .exchangeToMono(response -> response.bodyToMono(AtlassianAccessToken.class))
                .block();

        assert atlassianAccessToken != null;

        log.info("Saving Access Token for user: {}", userId);

        AtlassianAccessToken tokenWithUserInfo = AtlassianAccessToken.from(atlassianAccessToken)
                .userId(userId)
                .created(ZonedDateTime.now())
                .updated(ZonedDateTime.now())
                .build();

        log.info("Saving a new access Access Token for user: {}", userId);
        log.info("Token has expiresIn: {}", tokenWithUserInfo.expiresIn() != null);

        UserPreferences userPreferences = userPreferencesService.get(userId);
        userPreferences.setAtlassianAccessToken(tokenWithUserInfo);

        userPreferencesService.update(userId, userPreferences);
    }

    public String accessibleResources() {
        String accessibleResources = apiWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment(OAUTH_PATH)
                        .pathSegment(TOKEN_PATH)
                        .pathSegment(ACCESSIBLE_RESOURCES_PATH)
                        .build())
                .exchangeToMono(response -> response.bodyToMono(String.class))
                .block();

        log.info(accessibleResources);
        return accessibleResources;
    }
}
