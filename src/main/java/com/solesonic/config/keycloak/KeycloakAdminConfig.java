package com.solesonic.config.keycloak;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

/**
 * Keycloak's admin REST API for the user realm, on the {@code keycloak-admin} client's own
 * client-credentials token. That client needs only {@code realm-management/view-users}.
 */
@Configuration
public class KeycloakAdminConfig {
    public static final String KEYCLOAK_ADMIN_REST_CLIENT = "KEYCLOAK_ADMIN_REST_CLIENT";
    static final String KEYCLOAK_ADMIN_AUTHORIZED_CLIENT_MANAGER = "KEYCLOAK_ADMIN_AUTHORIZED_CLIENT_MANAGER";
    static final String KEYCLOAK_ADMIN_REGISTRATION = "keycloak-admin";
    private static final String KEYCLOAK_ADMIN_PRINCIPAL = "keycloak-admin";

    @Bean(defaultCandidate = false)
    @Qualifier(KEYCLOAK_ADMIN_AUTHORIZED_CLIENT_MANAGER)
    public OAuth2AuthorizedClientManager keycloakAdminAuthorizedClientManager(ClientRegistrationRepository clientRegistrationRepository,
                                                                              OAuth2AuthorizedClientService authorizedClientService) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientService);

        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build());

        return manager;
    }

    /**
     * Authorizes through the manager directly, as {@code A2AWebClientConfig} does: the sweep runs on a
     * scheduler thread with no servlet request bound.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(KEYCLOAK_ADMIN_REST_CLIENT)
    public RestClient keycloakAdminRestClient(@Qualifier(KEYCLOAK_ADMIN_AUTHORIZED_CLIENT_MANAGER) OAuth2AuthorizedClientManager authorizedClientManager,
                                              @Value("${solesonic.user.keycloak.admin-uri}") String adminUri) {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId(KEYCLOAK_ADMIN_REGISTRATION)
                .principal(KEYCLOAK_ADMIN_PRINCIPAL)
                .build();

        return RestClient.builder()
                .baseUrl(adminUri)
                .requestInterceptor((request, body, execution) -> {
                    OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);

                    if (authorizedClient == null) {
                        throw new IllegalStateException("No access token was issued for client registration '" + KEYCLOAK_ADMIN_REGISTRATION + "'");
                    }

                    request.getHeaders().setBearerAuth(authorizedClient.getAccessToken().getTokenValue());

                    return execution.execute(request, body);
                })
                .build();
    }
}
