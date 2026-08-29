package com.solesonic.config.openai;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The small model the RAG pipeline runs its own prompts against — query rewriting, multi-query
 * expansion and document reranking — served by the same OpenAI-compatible server as chat
 * ({@code spring.ai.openai.base-url}).
 * <p>
 * Separate from {@link ToolCallOpenAiConfig} even though both are "task" models and share a host:
 * the two are asked for completely different things (a rewritten query versus a tool call), so
 * keeping them as distinct beans lets either be pointed at a model of its own later without
 * disturbing the other.
 * <p>
 * Temperature is pinned at zero because a rewritten query and a rerank verdict are both supposed to
 * be reproducible for the same input.
 */
@Configuration
public class RagTaskOpenAiConfig {
    public static final String RAG_TASK_CHAT_MODEL = "RAG_TASK_CHAT_MODEL";

    /**
     * {@code defaultCandidate = false} is required, not stylistic: an unqualified
     * {@link OpenAiChatModel} injection point would otherwise see this bean as an ambiguous
     * candidate alongside every other hand-built model in {@code config/openai}. Inject by
     * {@link Qualifier} only.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(RAG_TASK_CHAT_MODEL)
    public OpenAiChatModel ragTaskChatModel(@Value("${spring.ai.openai.api-key}") String apiKey,
                                            @Value("${spring.ai.openai.base-url}") String baseUrl) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .temperature(0.0)
                .build();

        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }
}
