package com.solesonic.mcp.client;

import com.solesonic.model.security.McpFilterService;
import jakarta.annotation.Nonnull;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

@Component
public class McpSyncClientExchangeFilterFunction implements ExchangeFilterFunction {
    private static final Logger log = LoggerFactory.getLogger(McpSyncClientExchangeFilterFunction.class);

    private final TokenExchangeService tokenExchangeService;
    private final McpFilterService mcpFilterService;

    public McpSyncClientExchangeFilterFunction(TokenExchangeService tokenExchangeService,
                                               McpFilterService mcpFilterService) {
        this.tokenExchangeService = tokenExchangeService;
        this.mcpFilterService = mcpFilterService;
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
    public @Nonnull Mono<ClientResponse> filter(@Nonnull ClientRequest request, @Nonnull ExchangeFunction exchangeFunction) {
        String userToken = mcpFilterService.userToken(request);

        if (StringUtils.isNotEmpty(userToken)) {
            log.info("Propagating user JWT to outbound MCP client streaming request {}", request.url());

            //Exchange the current user token for an OBO token
            return tokenExchangeService.exchangeToken(userToken)
                    .flatMap(exchangedToken -> {
                        var requestWithUserToken = ClientRequest.from(request)
                                .headers(headers -> headers.setBearerAuth(exchangedToken))
                                .build();
                        return exchangeFunction.exchange(requestWithUserToken);
                    });
        } else {
            log.info("Standard client credentials MCP request.");

            var accessToken = mcpFilterService.getClientCredentialsAccessToken();

            var requestWithToken = ClientRequest.from(request)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .build();

            return exchangeFunction.exchange(requestWithToken);
        }
    }

    /**
     * Configure a {@link WebClient} to use this exchange filter function.
     */
    public Consumer<WebClient.Builder> configuration() {
        return builder -> builder.filter(this);
    }
}
