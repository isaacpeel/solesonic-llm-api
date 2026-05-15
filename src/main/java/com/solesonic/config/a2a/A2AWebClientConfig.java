package com.solesonic.config.a2a;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@ConditionalOnProperty(prefix = "solesonic.a2a", name = "enabled", havingValue = "true")
public class A2AWebClientConfig {

    public static final String A2A_WEB_CLIENT = "a2aWebClient";
    static final String A2A_AUTHORIZED_CLIENT_MANAGER = "a2aAuthorizedClientManager";

    @Bean(A2A_AUTHORIZED_CLIENT_MANAGER)
    public OAuth2AuthorizedClientManager a2aAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {

        var authorizedClientProvider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();

        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(authorizedClientProvider);

        return manager;
    }

    @Bean(A2A_WEB_CLIENT)
    public WebClient a2aWebClient(
            @Qualifier(A2A_AUTHORIZED_CLIENT_MANAGER) OAuth2AuthorizedClientManager authorizedClientManager) {

        var oauth2Filter = new ServletOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth2Filter.setDefaultClientRegistrationId("mcp-client");

        return WebClient.builder()
                .apply(oauth2Filter.oauth2Configuration())
                .build();
    }
}
