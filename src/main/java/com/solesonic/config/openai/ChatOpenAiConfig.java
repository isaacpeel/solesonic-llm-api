package com.solesonic.config.openai;

import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The main/default chat model, backed by a llama.cpp {@code llama-server} instance over its
 * OpenAI-compatible API. Configured separately from the Ollama-backed models — its own host and
 * model, no auth — so main chat inference is pinned to a URI dedicated to it, exactly as
 * {@code EtlOllamaConfig} and {@code VisionOllamaConfig} are for their purposes.
 */
@Configuration
public class ChatOpenAiConfig {
    public static final String CHAT_CHAT_MODEL = "CHAT_CHAT_MODEL";

    /**
     * {@code defaultCandidate = false} is required, not stylistic: without it
     * {@code ChatConfig#defaultChatClient} would see this bean as an ambiguous candidate alongside
     * whatever {@code spring-ai-starter-model-openai}'s own auto-configuration produces. Inject this
     * bean by {@link Qualifier} only.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(CHAT_CHAT_MODEL)
    public OpenAiChatModel chatChatModel(@Value("${solesonic.llm.chat.openai.host}") String baseUrl,
                                         @Value("${solesonic.llm.chat.model}") String chatModel) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(new NoopApiKey())
                .model(chatModel)
                .build();

        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }
}
