package com.solesonic.mcp.client;

import jakarta.annotation.Nonnull;
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
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

@Component
public class McpSyncClientExchangeFilterFunction implements ExchangeFilterFunction {

    private static final Logger log = LoggerFactory.getLogger(McpSyncClientExchangeFilterFunction.class);
    public static final String CLIENT_CREDENTIALS_CLIENT = "client-credentials-client";
    public static final String SOLESONIC_MCP_READ = "solesonic-mcp.read";
    private static final String CLIENT_CREDENTIALS_CLIENT_REGISTRATION_ID = "mcp-client";

    private final ClientCredentialsOAuth2AuthorizedClientProvider clientCredentialTokenProvider = new ClientCredentialsOAuth2AuthorizedClientProvider();
    private final ClientRegistrationRepository clientRegistrationRepository;

    private final TokenExchangeService tokenExchangeService;

    public McpSyncClientExchangeFilterFunction(ClientRegistrationRepository clientRegistrationRepository,
                                               TokenExchangeService tokenExchangeService) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.tokenExchangeService = tokenExchangeService;
    }

    /**
     * Add an {@code access_token} to the request sent to the MCP server.
     * <p>
     * For user-initiated HTTP requests (Servlet request present), forward the user's JWT from
     * SecurityContextHolder as Authorization: Bearer <token>.
     * <p>
     * For non-request contexts (no ServletRequest), fall back to client_credentials to obtain
     * an application token. Never use client_credentials when a user JWT is available.
     */
    @Override
    public @Nonnull Mono<ClientResponse> filter(@Nonnull ClientRequest request, @Nonnull ExchangeFunction next) {
        boolean hasServletRequest = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes;
        if (hasServletRequest) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                //Exchange an OBO token for the user to pass to the MCP server.
                String userToken = jwt.getTokenValue();
                log.debug("Propagating user JWT to outbound request {}", request.url());


                return tokenExchangeService.exchangeToken(userToken)
                        .flatMap(exchangedToken -> {
                            var requestWithUserToken = ClientRequest.from(request)
                                    .headers(headers -> headers.setBearerAuth(exchangedToken))
                                    .build();
                            return next.exchange(requestWithUserToken);
                        });
            } else {
                // In a user request without a JWT, fail fast instead of using client_credentials
                log.warn("User HTTP request detected but no Jwt present in SecurityContext for outbound call to {}", request.url());
                return Mono.error(new IllegalStateException("Missing user Jwt for outbound request"));
            }
        } else {
            var accessToken = getClientCredentialsAccessToken();

            var requestWithToken = ClientRequest.from(request)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .build();

            return next.exchange(requestWithToken);
        }
    }

    private String getClientCredentialsAccessToken() {
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

    /**
     * Configure a {@link WebClient} to use this exchange filter function.
     */
    public Consumer<WebClient.Builder> configuration() {
        return builder -> builder.filter(this);
    }

}
