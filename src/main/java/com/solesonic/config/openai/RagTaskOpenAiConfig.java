package com.solesonic.config.openai;

import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The small model the RAG pipeline runs its own prompts against — query rewriting, multi-query
 * expansion and document reranking — served by an OpenAI-compatible server of its own.
 * <p>
 * Separate from {@link ToolCallOpenAiConfig} even though both are "task" models: the two are asked
 * for completely different things (a rewritten query versus a tool call), so pointing them at one
 * host would tie a reranker's latency to whatever model happens to route tool calls well.
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
    public OpenAiChatModel ragTaskChatModel(@Value("${solesonic.llm.rag-task.openai.host}") String baseUrl,
                                            @Value("${solesonic.llm.rag-task.model}") String ragTaskModel) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(new NoopApiKey())
                .model(ragTaskModel)
                .temperature(0.0)
                .build();

        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }
}
