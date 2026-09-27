package com.solesonic.service.atlassian;

import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.user.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLDecoder;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JiraAuthServiceTest {

    private static final String AUTHORIZE_URI = "https://auth.atlassian.com";
    private static final String CALLBACK_URI = "https://example.test/atlassian/auth/callback";
    private static final String CLIENT_ID = "client-id";

    private UserRequestContext userRequestContext;
    private UUID userId;

    @BeforeEach
    void beforeEach() {
        userId = UUID.randomUUID();
        userRequestContext = mock(UserRequestContext.class);

        when(userRequestContext.getUserId()).thenReturn(userId);
    }

    private JiraAuthService jiraAuthService() {
        UserPreferencesService userPreferencesService = mock(UserPreferencesService.class);
        WebClient authWebClient = mock(WebClient.class);
        WebClient apiWebClient = mock(WebClient.class);

        JiraAuthService jiraAuthService =
                new JiraAuthService(userRequestContext, userPreferencesService, authWebClient, apiWebClient);

        ReflectionTestUtils.setField(jiraAuthService, "jiraAuthUri", AUTHORIZE_URI);
        ReflectionTestUtils.setField(jiraAuthService, "authCallbackUri", CALLBACK_URI);
        ReflectionTestUtils.setField(jiraAuthService, "clientId", CLIENT_ID);

        return jiraAuthService;
    }

    /**
     * The whole point of the dedicated-callback story: redirect_uri must come from configuration
     * rather than being hardcoded, so pointing the UI at a new callback path is a config change,
     * not a code change. This pins that {@code jira.api.auth.callback.uri} is what ends up on the
     * wire, character for character, since Atlassian compares it exactly against its registered
     * "Valid Redirect URIs".
     */
    @Test
    void buildsTheAuthorizeUriWithTheConfiguredCallbackAsRedirectUri() {
        String authUri = jiraAuthService().authUri();

        String redirectUriParam = redirectUriParam(authUri);

        assertThat(redirectUriParam).isEqualTo(CALLBACK_URI);
    }

    /**
     * {@code state} is log correlation only. The callback resolves the user from its own
     * authenticated request, never from this value, so a forged state cannot attach someone else's
     * Atlassian account.
     */
    @Test
    void carriesTheUserIdAsStateForLogCorrelation() {
        String authUri = jiraAuthService().authUri();

        assertThat(authUri).contains("state=" + userId);
    }

    private static String redirectUriParam(String authUri) {
        String marker = "redirect_uri=";
        int start = authUri.indexOf(marker) + marker.length();
        int end = authUri.indexOf('&', start);
        String rawValue = end == -1 ? authUri.substring(start) : authUri.substring(start, end);

        return URLDecoder.decode(rawValue, UTF_8);
    }
}
