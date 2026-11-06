package com.solesonic.config.openai;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * The model that reads idle conversations and proposes what to remember about their owner, served
 * by the same OpenAI-compatible server as chat. It never follows a chat's {@code /model} selection:
 * what is learned must not depend on which model a conversation happened to be switched to.
 * <p>
 * Temperature is pinned at zero so the same window yields the same proposal.
 */
@Configuration
public class MemoryOpenAiConfig {
    public static final String MEMORY_CHAT_MODEL = "MEMORY_CHAT_MODEL";

    /**
     * {@code defaultCandidate = false} is required, not stylistic: an unqualified
     * {@link OpenAiChatModel} injection point would otherwise see this bean as an ambiguous
     * candidate alongside every other hand-built model in {@code config/openai}. Inject by
     * {@link Qualifier} only.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(MEMORY_CHAT_MODEL)
    public OpenAiChatModel memoryChatModel(@Value("${spring.ai.openai.api-key}") String apiKey,
                                           @Value("${spring.ai.openai.base-url}") String baseUrl,
                                           @Value("${solesonic.llm.memory.model}") String memoryModel,
                                           @Value("${solesonic.llm.memory.openai.read-timeout}") Duration readTimeout,
                                           List<OpenAiHttpClientBuilderCustomizer> httpClientBuilderCustomizers) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(memoryModel)
                .timeout(readTimeout)
                .temperature(0.0)
                .build();

        return OpenAiChatModel.builder()
                .options(options)
                .httpClientBuilderCustomizers(httpClientBuilderCustomizers)
                .build();
    }
}
