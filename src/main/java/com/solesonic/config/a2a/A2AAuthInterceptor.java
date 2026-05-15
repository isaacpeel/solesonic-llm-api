package com.solesonic.config.a2a;

import com.solesonic.mcp.client.IdentityToolCallback;
import com.solesonic.mcp.client.TokenExchangeService;
import com.solesonic.model.security.McpFilterService;
import io.a2a.client.transport.spi.interceptors.ClientCallContext;
import io.a2a.client.transport.spi.interceptors.ClientCallInterceptor;
import io.a2a.client.transport.spi.interceptors.PayloadAndHeaders;
import io.a2a.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static com.solesonic.mcp.client.IdentityToolCallback.SECURITY_CONTEXT_KEY;
import static com.solesonic.mcp.client.IdentityToolCallback.USER_TOKEN;

@Component
@ConditionalOnProperty(prefix = "solesonic.a2a", name = "enabled", havingValue = "true")
public class A2AAuthInterceptor extends ClientCallInterceptor {

    private static final Logger log = LoggerFactory.getLogger(A2AAuthInterceptor.class);

    private final TokenExchangeService tokenExchangeService;
    private final McpFilterService mcpFilterService;

    public A2AAuthInterceptor(TokenExchangeService tokenExchangeService, McpFilterService mcpFilterService) {
        this.tokenExchangeService = tokenExchangeService;
        this.mcpFilterService = mcpFilterService;
    }

    @Override
    public PayloadAndHeaders intercept(String methodName, Object payload, Map<String, String> headers,
                                       AgentCard agentCard, ClientCallContext clientCallContext) {
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
