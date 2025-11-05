package com.solesonic.config;

import com.solesonic.mcp.client.IdentityToolCallback;
import com.solesonic.mcp.client.TokenExchangeService;
import com.solesonic.model.security.McpFilterService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.Map;

import static com.solesonic.mcp.client.IdentityToolCallback.SECURITY_CONTEXT_KEY;
import static com.solesonic.mcp.client.IdentityToolCallback.USER_TOKEN;

@Configuration
public class WebClientConfig {
    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    @Bean
    public WebClient.Builder webClientBuilder(TokenExchangeService tokenExchangeService, McpFilterService mcpFilterService) {
        return WebClient.builder()
                .filter((request, next) -> Mono.deferContextual(contextView -> {

                    log.info("Filtering mcp request: {}", request.url().getPath());
                    log.info("User info in request: {}", request.url().getUserInfo());
                    log.info("WebClient filter executing - checking for security context");

                    String userToken = threadLocalUserToken();

                    if (StringUtils.isEmpty(userToken)) {
                        log.info("User token for streaming is not found, using client credentials.");

                        // No user token — use client credentials token
                        String accessToken = mcpFilterService.getClientCredentialsAccessToken();

                        ClientRequest newRequest = ClientRequest.from(request)
                                .headers(headers -> headers.setBearerAuth(accessToken))
                                .build();

                        return next.exchange(newRequest);
                    }

                    log.info("User identity found, exchanging for an OBO token.");

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

    private String threadLocalUserToken() {
        log.info("Looking for users token in identity context");

        if(!IdentityToolCallback.hasContext()) {
            log.info("No identity context found.");
            return null;
        }

        Context reactiveContext = IdentityToolCallback.toolCallContext();
        Map<String, Object> securityContext = reactiveContext.get(SECURITY_CONTEXT_KEY);

        if (securityContext.containsKey(USER_TOKEN)) {
            log.info("User Token found.");
            return securityContext.get(USER_TOKEN).toString();
        }

        log.info("No user token found.");
        return null;
    }
}