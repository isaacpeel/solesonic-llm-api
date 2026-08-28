package com.solesonic.config.openai;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The model a slash command routes through when it has to turn a user's message into a single tool
 * call, served by an OpenAI-compatible server of its own. Kept apart from
 * {@link RagTaskOpenAiConfig} so tool-call routing can be pointed at whichever model calls tools
 * reliably, independently of what rewrites RAG queries.
 */
@Configuration
public class ToolCallOpenAiConfig {
    public static final String TOOL_CALL_CHAT_MODEL = "TOOL_CALL_CHAT_MODEL";

    /**
     * {@code defaultCandidate = false} is required, not stylistic: an unqualified
     * {@link OpenAiChatModel} injection point would otherwise see this bean as an ambiguous
     * candidate alongside every other hand-built model in {@code config/openai}. Inject by
     * {@link Qualifier} only.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(TOOL_CALL_CHAT_MODEL)
    public OpenAiChatModel toolCallChatModel(@Value("${spring.ai.openai.api-key}") String apiKey,
                                             @Value("${solesonic.llm.tool-call.model}") String toolCallModel) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .model(toolCallModel)
                .build();

        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }
}
