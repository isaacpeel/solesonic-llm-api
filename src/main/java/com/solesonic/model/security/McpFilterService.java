package com.solesonic.model.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.service.security.RequestBodyService;
import com.solesonic.service.user.UserTokenCacheService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientRequest;

import static com.solesonic.service.user.UserTokenCacheService.USER_TOKEN_KEY;

@Service
public class McpFilterService {
    private static final Logger log = LoggerFactory.getLogger(McpFilterService.class);

    public static final String CLIENT_CREDENTIALS_CLIENT = "client-credentials-client";
    public static final String SOLESONIC_MCP_READ = "solesonic-mcp.read";
    private static final String CLIENT_CREDENTIALS_CLIENT_REGISTRATION_ID = "mcp-client";

    public static final String PARAMS = "params";
    public static final String META = "_meta";

    private final RequestBodyService requestBodyService;
    private final ObjectMapper objectMapper;
    private final UserTokenCacheService userTokenCacheService;
    private final ClientRegistrationRepository clientRegistrationRepository;

    private final ClientCredentialsOAuth2AuthorizedClientProvider clientCredentialTokenProvider = new ClientCredentialsOAuth2AuthorizedClientProvider();

    public McpFilterService(RequestBodyService requestBodyService,
                            ObjectMapper objectMapper,
                            UserTokenCacheService userTokenCacheService,
                            ClientRegistrationRepository clientRegistrationRepository) {
        this.requestBodyService = requestBodyService;
        this.objectMapper = objectMapper;
        this.userTokenCacheService = userTokenCacheService;
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    /**
     * Try to find a user token for a streamed MCP request
     *
     * @param clientRequest the users request to analyze
     * @return the current users access token or else null
     */
    public String streamingUserToken(ClientRequest clientRequest) {
        log.info("Getting a user token from a streaming MCP client request");

        String capturedBody = requestBodyService.captureBody(clientRequest);

        String cacheKey = extractCacheKey(capturedBody);

        if(StringUtils.isEmpty(cacheKey)) {
            return null;
        }

        return userTokenCacheService.token(cacheKey);
    }

    /**
     * Looks in the current client request to the MCP server and tries to find the current
     * users auth token
     *
     * @param clientRequest the clients request to analyze
     * @return the current users access token or else null
     */
    public String userToken(ClientRequest clientRequest) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            log.info("Getting a user token from a standard MCP client request");
            return jwt.getTokenValue();
        }

        return streamingUserToken(clientRequest);
    }

    /**
     * Extracts the cache_key from {@code $.params.meta.cache_key} in the JSON body.
     *
     * @param jsonString the JSON request body as a string
     * @return the cache_key value, or null if not found or invalid JSON
     */
    private String extractCacheKey(String jsonString) {
        try {
            JsonNode rootNode = objectMapper.readTree(jsonString);

            JsonNode paramsNode = rootNode.path(PARAMS);
            JsonNode metaNode = paramsNode.path(META);
            JsonNode cacheKeyNode = metaNode.path(USER_TOKEN_KEY);

            if (!cacheKeyNode.isMissingNode() && cacheKeyNode.isTextual()) {
                return cacheKeyNode.asText();
            }

            return null;
        } catch (Exception exception) {
            log.debug("Failed to parse JSON or extract cache_key: {}", exception.getMessage());

            return null;
        }
    }

    public String getClientCredentialsAccessToken() {
        var clientRegistration = this.clientRegistrationRepository
                .findByRegistrationId(CLIENT_CREDENTIALS_CLIENT_REGISTRATION_ID);

        var authRequest = OAuth2AuthorizationContext.withClientRegistration(clientRegistration)
                .principal(new AnonymousAuthenticationToken(CLIENT_CREDENTIALS_CLIENT, CLIENT_CREDENTIALS_CLIENT, AuthorityUtils.createAuthorityList(SOLESONIC_MCP_READ)))
                .build();

        OAuth2AuthorizedClient oAuth2AuthorizedClient = clientCredentialTokenProvider.authorize(authRequest);
        assert oAuth2AuthorizedClient != null;

        OAuth2AccessToken oAuth2AccessToken = oAuth2AuthorizedClient.getAccessToken();
        assert oAuth2AccessToken != null;

        return oAuth2AccessToken.getTokenValue();
    }
}
