package com.solesonic.config.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * An OpenAI SDK client for the endpoint chat talks to, with the same base URL and key, for the parts
 * of the OpenAI API that a {@code ChatModel} does not cover — listing models.
 * <p>
 * Spring AI builds the chat model's own client privately and does not expose it, so this is a second
 * client to the same endpoint, on Spring AI's own HTTP transport.
 */
@Configuration
public class OpenAiClientConfig {
    public static final String OPENAI_CLIENT = "OPENAI_CLIENT";

    @Bean(defaultCandidate = false)
    @Qualifier(OPENAI_CLIENT)
    public OpenAIClient openAiClient(@Value("${spring.ai.openai.api-key}") String apiKey,
                                     @Value("${spring.ai.openai.base-url}") String baseUrl,
                                     @Value("${spring.ai.openai.timeout}") Duration timeout) {
        ClientOptions clientOptions = ClientOptions.builder()
                .httpClient(SpringAiOpenAiHttpClient.builder().timeout(timeout).build())
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .timeout(timeout)
                .build();

        return new OpenAIClientImpl(clientOptions);
    }
}
