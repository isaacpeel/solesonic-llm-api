package com.solesonic.config.a2a;

import org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LiteLlmA2AAuthInterceptorTest {

    private final LiteLlmA2AAuthInterceptor interceptor = new LiteLlmA2AAuthInterceptor("sk-izzybot");

    @Test
    void putsTheVirtualKeyInTheLiteLlmHeader() {
        PayloadAndHeaders payloadAndHeaders = interceptor.intercept("SendStreamingMessage", "payload", Map.of(), null, null);

        assertThat(payloadAndHeaders.getHeaders()).containsEntry("x-litellm-api-key", "Bearer sk-izzybot");
        assertThat(payloadAndHeaders.getPayload()).isEqualTo("payload");
    }

    /**
     * The user's token must never leave for the gateway, whichever spelling put it on the request.
     */
    @Test
    void removesEveryAuthorizationHeaderWhateverItsCase() {
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer user-jwt",
                "authorization", "Bearer another-jwt",
                "X-Trace", "trace-id");

        PayloadAndHeaders payloadAndHeaders = interceptor.intercept("SendMessage", "payload", headers, null, null);

        assertThat(payloadAndHeaders.getHeaders())
                .doesNotContainKeys("Authorization", "authorization")
                .containsEntry("X-Trace", "trace-id");
    }

    @Test
    void replacesAVirtualKeyAlreadyOnTheRequest() {
        Map<String, String> headers = Map.of("X-LiteLLM-Api-Key", "Bearer someone-elses-key");

        PayloadAndHeaders payloadAndHeaders = interceptor.intercept("SendMessage", "payload", headers, null, null);

        assertThat(payloadAndHeaders.getHeaders()).containsExactly(Map.entry("x-litellm-api-key", "Bearer sk-izzybot"));
    }
}
