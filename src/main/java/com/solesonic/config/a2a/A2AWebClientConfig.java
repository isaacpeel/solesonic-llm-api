package com.solesonic.config.a2a;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Configuration
public class A2AWebClientConfig {

    public static final String A2A_WEB_CLIENT = "a2aWebClient";
    static final String A2A_AUTHORIZED_CLIENT_MANAGER = "a2aAuthorizedClientManager";
    public static final String MCP_CLIENT = "mcp-client";
    private static final String A2A_CLIENT_PRINCIPAL = "a2a-client";

    private final A2AClientProperties a2AClientProperties;

    public A2AWebClientConfig(A2AClientProperties a2AClientProperties) {
        this.a2AClientProperties = a2AClientProperties;
    }

    @Bean(A2A_AUTHORIZED_CLIENT_MANAGER)
    public OAuth2AuthorizedClientManager a2aAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {

        var authorizedClientProvider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();

        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(authorizedClientProvider);

        return manager;
    }

    /**
     * Authorizes through the manager directly rather than through
     * {@code ServletOAuth2AuthorizedClientExchangeFilterFunction}, which silently sends the request
     * without a token whenever no servlet request is bound — as during agent discovery at startup.
     */
    @Bean(A2A_WEB_CLIENT)
    public WebClient a2aWebClient(
            @Qualifier(A2A_AUTHORIZED_CLIENT_MANAGER) OAuth2AuthorizedClientManager authorizedClientManager) {

        return WebClient.builder()
                .baseUrl(a2AClientProperties.baseUri())
                .filter(clientCredentialsFilter(authorizedClientManager))
                .build();
    }

    private ExchangeFilterFunction clientCredentialsFilter(OAuth2AuthorizedClientManager authorizedClientManager) {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId(MCP_CLIENT)
                .principal(A2A_CLIENT_PRINCIPAL)
                .build();

        return (request, next) -> Mono.fromCallable(() -> authorizedClientManager.authorize(authorizeRequest))
                .subscribeOn(Schedulers.boundedElastic())
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("No A2A access token was issued for client registration '" + MCP_CLIENT + "'")))
                .map(OAuth2AuthorizedClient::getAccessToken)
                .map(accessToken -> ClientRequest.from(request)
                        .headers(headers -> headers.setBearerAuth(accessToken.getTokenValue()))
                        .build())
                .flatMap(next::exchange);
    }

}
