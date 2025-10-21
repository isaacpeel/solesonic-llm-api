package com.solesonic.model.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.stereotype.Service;

@Service
public class McpFilterService {
    private static final Logger log = LoggerFactory.getLogger(McpFilterService.class);

    public static final String CLIENT_CREDENTIALS_CLIENT = "client-credentials-client";
    public static final String SOLESONIC_MCP_READ = "solesonic-mcp.read";
    private static final String CLIENT_CREDENTIALS_CLIENT_REGISTRATION_ID = "mcp-client";


    private final ClientRegistrationRepository clientRegistrationRepository;

    private final ClientCredentialsOAuth2AuthorizedClientProvider clientCredentialTokenProvider = new ClientCredentialsOAuth2AuthorizedClientProvider();

    public McpFilterService(ClientRegistrationRepository clientRegistrationRepository) {
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    public String getClientCredentialsAccessToken() {
        log.debug("Getting client credentials token");

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
