package com.solesonic.config;

import com.solesonic.mcp.client.TokenExchangeService;
import com.solesonic.model.security.McpFilterService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Configuration
public class WebClientConfig {
    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    @Bean
    public WebClient.Builder webClientBuilder(TokenExchangeService tokenExchangeService, McpFilterService mcpFilterService) {
        return WebClient.builder()
                // 👇 add our custom filter first
                .filter((request, next) -> Mono.deferContextual(contextView -> {
                    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

                    String userToken = null;

                    if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                        userToken = jwt.getTokenValue();
                    }

                    if (StringUtils.isEmpty(userToken)) {
                        log.info("User token for streaming is not found, using client credentials.");

                        // No user token — use client credentials token
                        String accessToken = mcpFilterService.getClientCredentialsAccessToken();

                        ClientRequest newRequest = ClientRequest.from(request)
                                .headers(headers -> headers.setBearerAuth(accessToken))
                                .build();

                        return next.exchange(newRequest);
                    }

                    log.info("User token for streaming found, exchanging for an OBO token.");

                    // Otherwise, use OBO token exchange
                    return tokenExchangeService.exchangeToken(userToken)
                            .flatMap(exchangedToken -> {
                                ClientRequest newRequest = ClientRequest.from(request)
                                        .headers(headers -> headers.setBearerAuth(exchangedToken))
                                        .build();

                                return next.exchange(newRequest);
                            });
                }));
    }
}
