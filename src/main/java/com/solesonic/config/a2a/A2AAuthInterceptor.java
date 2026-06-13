package com.solesonic.config.a2a;

import com.solesonic.mcp.client.IdentityToolCallback;
import com.solesonic.mcp.client.TokenExchangeService;
import com.solesonic.model.security.McpFilterService;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor;
import org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders;
import org.a2aproject.sdk.spec.AgentCard;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static com.solesonic.mcp.client.IdentityToolCallback.SECURITY_CONTEXT_KEY;
import static com.solesonic.mcp.client.IdentityToolCallback.USER_TOKEN;

@Component
public class A2AAuthInterceptor extends ClientCallInterceptor {

    private static final Logger log = LoggerFactory.getLogger(A2AAuthInterceptor.class);

    private final TokenExchangeService tokenExchangeService;
    private final McpFilterService mcpFilterService;

    public A2AAuthInterceptor(TokenExchangeService tokenExchangeService, McpFilterService mcpFilterService) {
        this.tokenExchangeService = tokenExchangeService;
        this.mcpFilterService = mcpFilterService;
    }

    @Override
    @NonNull
    public PayloadAndHeaders intercept(@NonNull String methodName,
                                       Object payload,
                                       @NonNull Map<String, String> headers,
                                       AgentCard agentCard,
                                       ClientCallContext clientCallContext) {
        String accessToken = resolveAccessToken();

        Map<String, String> authenticatedHeaders = new HashMap<>(headers);
        authenticatedHeaders.put(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);

        return new PayloadAndHeaders(payload, authenticatedHeaders);
    }

    private String resolveAccessToken() {
        if (!IdentityToolCallback.hasContext()) {
            log.debug("No identity context found, using client credentials for A2A call.");
            return mcpFilterService.getClientCredentialsAccessToken();
        }

        Map<String, Object> securityContext = IdentityToolCallback.toolCallContext().get(SECURITY_CONTEXT_KEY);

        if (!securityContext.containsKey(USER_TOKEN)) {
            log.debug("No user token in context, using client credentials for A2A call.");
            return mcpFilterService.getClientCredentialsAccessToken();
        }

        String userToken = securityContext.get(USER_TOKEN).toString();
        log.debug("Exchanging user token for OBO token for A2A call.");
        return tokenExchangeService.exchangeToken(userToken).block();
    }
}
