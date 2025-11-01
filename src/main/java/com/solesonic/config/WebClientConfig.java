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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.Map;
import java.util.Set;

@Configuration
public class WebClientConfig {
    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);
    public static final String SECURITY_CONTEXT_ATTRIBUTES = "org.springframework.security.SECURITY_CONTEXT_ATTRIBUTES";

    @Bean
    @SuppressWarnings("rawtypes")
    public WebClient.Builder webClientBuilder(TokenExchangeService tokenExchangeService, McpFilterService mcpFilterService) {
        return WebClient.builder()
                .filter((request, next) -> Mono.deferContextual(contextView -> {
                    log.debug("WebClient filter executing - checking for security context");

                    String userToken = null;

                    // Strategy 1: Check ThreadLocal from MCP tool execution
                    if (SecurityContextPropagatingMcpToolCallback.hasReactiveContext()) {
                        Context toolContext = SecurityContextPropagatingMcpToolCallback.getReactiveContext();
                        if (toolContext.hasKey(SecurityContextPropagatingMcpToolCallback.SECURITY_CONTEXT_KEY)) {
                            Map<String, Object> securityMap = toolContext.get(
                                    SecurityContextPropagatingMcpToolCallback.SECURITY_CONTEXT_KEY);

                            log.debug("Found security context in ThreadLocal from MCP tool");

                            if (securityMap.get("jwtAuthenticationToken") instanceof JwtAuthenticationToken jwt) {
                                userToken = jwt.getToken().getTokenValue();
                                log.debug("Extracted token from JwtAuthenticationToken in ThreadLocal");
                            } else if (securityMap.get("jwt") instanceof Jwt jwt) {
                                userToken = jwt.getTokenValue();
                                log.debug("Extracted token from Jwt in ThreadLocal");
                            } else if (securityMap.get("authentication") instanceof Authentication auth
                                    && auth.getPrincipal() instanceof Jwt jwt) {
                                userToken = jwt.getTokenValue();
                                log.debug("Extracted token from Authentication principal in ThreadLocal");
                            }
                        }
                    }

                    // Strategy 2: Check SecurityContextHolder (works for non-reactive contexts)
                    if (StringUtils.isEmpty(userToken)) {
                        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                            userToken = jwt.getTokenValue();
                            log.debug("Token found via SecurityContextHolder");
                        }
                    }

                    // Strategy 3: Check reactive context (works for streaming endpoints)
                    if (StringUtils.isEmpty(userToken) && contextView.hasKey(SECURITY_CONTEXT_ATTRIBUTES)) {
                        Object securityAttributes = contextView.get(SECURITY_CONTEXT_ATTRIBUTES);
                        log.debug("Security attributes found in reactive context: {}", securityAttributes);

                        if (securityAttributes instanceof Map attributesMap) {
                            Set keySet = attributesMap.keySet();

                            for (Object key : keySet) {
                                Object attributeValue = attributesMap.get(key);

                                if (attributeValue instanceof JwtAuthenticationToken jwt) {
                                    userToken = jwt.getToken().getTokenValue();
                                    log.debug("Token found via reactive context");
                                    break;
                                }
                            }
                        }
                    }

                    if (StringUtils.isEmpty(userToken)) {
                        log.info("User token for streaming is not found, using client credentials. " +
                                        "ThreadLocal context: {}, Reactive context keys: {}",
                                SecurityContextPropagatingMcpToolCallback.hasReactiveContext(),
                                contextView);

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