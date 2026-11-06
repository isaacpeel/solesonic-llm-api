package com.solesonic.config.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * An OpenAI SDK client for image generation, on the same endpoint and key chat uses.
 * <p>
 * A client of its own, not {@code OPENAI_CLIENT}, only because of its timeout: a generation can run
 * for minutes, and the model-list client must stay short. It never retries, because a retry is a
 * second generation on a GPU with no admission control, and a timed-out call keeps running upstream.
 * <p>
 * The customizers are what put {@code UserLlmKeyInterceptor} on it, so a call that forgets the user's
 * key is refused rather than sent on the shared one.
 */
@Configuration
public class ImageOpenAiConfig {
    public static final String IMAGE_OPENAI_CLIENT = "IMAGE_OPENAI_CLIENT";

    @Bean(defaultCandidate = false)
    @Qualifier(IMAGE_OPENAI_CLIENT)
    public OpenAIClient imageOpenAiClient(@Value("${spring.ai.openai.api-key}") String apiKey,
                                          @Value("${spring.ai.openai.base-url}") String baseUrl,
                                          @Value("${solesonic.llm.image.read-timeout}") Duration readTimeout,
                                          List<OpenAiHttpClientBuilderCustomizer> httpClientBuilderCustomizers) {
        SpringAiOpenAiHttpClient.Builder httpClientBuilder = SpringAiOpenAiHttpClient.builder().timeout(readTimeout);

        httpClientBuilderCustomizers.forEach(customizer -> customizer.customize(httpClientBuilder));

        ClientOptions clientOptions = ClientOptions.builder()
                .httpClient(httpClientBuilder.build())
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .timeout(readTimeout)
                .maxRetries(0)
                .build();

        return new OpenAIClientImpl(clientOptions);
    }
}
