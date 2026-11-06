package com.solesonic.config.a2a;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class LiteLlmA2AWebClientConfig {

    public static final String LITELLM_A2A_WEB_CLIENT = "liteLlmA2AWebClient";
    public static final String LITELLM_API_KEY_HEADER = "x-litellm-api-key";

    /**
     * Carries the virtual key in LiteLLM's own header and has no OAuth filter, so nothing on it can put
     * a token in {@code Authorization}.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(LITELLM_A2A_WEB_CLIENT)
    public WebClient liteLlmA2AWebClient(A2AClientProperties a2AClientProperties) {
        A2AClientProperties.Litellm litellm = a2AClientProperties.litellm();

        return WebClient.builder()
                .baseUrl(litellm.baseUri())
                .defaultHeader(LITELLM_API_KEY_HEADER, bearer(litellm.apiKey()))
                .build();
    }

    static String bearer(String apiKey) {
        return "Bearer " + apiKey;
    }
}
