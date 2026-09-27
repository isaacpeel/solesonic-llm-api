package com.solesonic.config.openai;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The vision model used to describe image attachments, served by the same OpenAI-compatible server
 * as chat ({@code spring.ai.openai.base-url}) but with its own model and timeout, exactly as
 * {@link EtlOpenAiConfig} does for enrichment.
 */
@Configuration
public class VisionOpenAiConfig {
    public static final String VISION_CHAT_MODEL = "VISION_CHAT_MODEL";

    /**
     * The generation budget has to hold the model's reasoning as well as the description. A
     * thinking vision model returns its reasoning first, so a budget that runs out mid-reasoning
     * yields an empty description rather than a truncated one: a text-dense photo transcribed label
     * by label spent 5.5k tokens reasoning before writing a 900-character description.
     * <p>
     * The matching context window is a server-launch concern here rather than a per-request option
     * — {@code --ctx-size} on {@code llama-server} — and needs to be large enough to hold the
     * image, that reasoning and the answer. 32k is the figure that works.
     */
    private static final int MAX_DESCRIPTION_TOKENS = 16384;

    /**
     * {@code defaultCandidate = false} is required, not stylistic: an unqualified
     * {@link OpenAiChatModel} injection point would otherwise see this bean as an ambiguous
     * candidate alongside every other hand-built model in {@code config/openai}. Inject by
     * {@link Qualifier} only.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(VISION_CHAT_MODEL)
    public OpenAiChatModel visionChatModel(@Value("${spring.ai.openai.api-key}") String apiKey,
                                           @Value("${spring.ai.openai.base-url}") String baseUrl,
                                           @Value("${solesonic.llm.vision.model}") String visionModel,
                                           @Value("${solesonic.llm.vision.openai.read-timeout}") Duration readTimeout) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(visionModel)
                .timeout(readTimeout)
                .temperature(0.2)
                .maxTokens(MAX_DESCRIPTION_TOKENS)
                .build();

        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }
}
