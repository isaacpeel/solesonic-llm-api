package com.solesonic.config.a2a;

import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor;
import org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders;
import org.a2aproject.sdk.spec.AgentCard;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;

import java.util.HashMap;
import java.util.Map;

import static com.solesonic.config.a2a.LiteLlmA2AWebClientConfig.LITELLM_API_KEY_HEADER;
import static com.solesonic.config.a2a.LiteLlmA2AWebClientConfig.bearer;

/**
 * Authenticates a call through the LiteLLM agent gateway with the virtual key alone. Any
 * {@code Authorization} header is removed rather than left for the gateway to ignore, so the user's
 * token can never leave on this path.
 */
public class LiteLlmA2AAuthInterceptor extends ClientCallInterceptor {

    private final String apiKeyHeaderValue;

    public LiteLlmA2AAuthInterceptor(String apiKey) {
        this.apiKeyHeaderValue = bearer(apiKey);
    }

    @Override
    @NonNull
    public PayloadAndHeaders intercept(@NonNull String methodName,
                                       Object payload,
                                       @NonNull Map<String, String> headers,
                                       AgentCard agentCard,
                                       ClientCallContext clientCallContext) {
        Map<String, String> gatewayHeaders = new HashMap<>(headers);
        gatewayHeaders.keySet().removeIf(headerName -> headerName.equalsIgnoreCase(HttpHeaders.AUTHORIZATION)
                || headerName.equalsIgnoreCase(LITELLM_API_KEY_HEADER));
        gatewayHeaders.put(LITELLM_API_KEY_HEADER, apiKeyHeaderValue);

        return new PayloadAndHeaders(payload, gatewayHeaders);
    }
}
