package com.solesonic.config;

import com.solesonic.mcp.client.SecurityContextPropagatingMcpToolCallback;
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
import reactor.util.context.Context;

import static com.solesonic.mcp.client.SecurityContextPropagatingMcpToolCallback.USER_TOKEN;

@Configuration
public class WebClientConfig {
    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    @Bean
    public WebClient.Builder webClientBuilder(TokenExchangeService tokenExchangeService, McpFilterService mcpFilterService) {
        return WebClient.builder()
                .filter((request, next) -> Mono.deferContextual(contextView -> {
                    log.info("WebClient filter executing - checking for security context");

                    String userToken = userToken();

                    if (StringUtils.isEmpty(userToken)) {
                        log.info("User token for streaming is not found, using client credentials. ThreadLocal context: {}", contextView);

                        contextView.stream().forEach(contextEntry -> log.info("entry: {}", contextEntry.getKey()));

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
                                log.info("OBO exchange success.");

                                return next.exchange(newRequest);
                            });
                }));
    }

    private String userToken() {
        String userToken = threadLocalUserToken();

        if (StringUtils.isNotEmpty(userToken)) {
            return userToken;
        }

        userToken = securityContextUserToken();

        if (StringUtils.isNotEmpty(userToken)) {
            return userToken;
        }

        return null;
    }

    private String securityContextUserToken() {
        log.info("Looking for user token in security context.");

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            log.info("Token found via SecurityContextHolder");
            return jwt.getTokenValue();
        }

        log.info("No Token found via SecurityContextHolder");
        return null;
    }

    private String threadLocalUserToken() {
        log.info("Looking for users token in thread local context");

        if(!SecurityContextPropagatingMcpToolCallback.hasContext()) {
            log.info("No Reactive context found.");
            return null;
        }

        Context reactiveContext = SecurityContextPropagatingMcpToolCallback.toolCallContext();

        if (reactiveContext.hasKey(USER_TOKEN)) {
            log.info("User Token found in ReactiveContext");
            return reactiveContext.get(USER_TOKEN);
        }

        log.info("No users token in thread local context");
        return null;
    }
}