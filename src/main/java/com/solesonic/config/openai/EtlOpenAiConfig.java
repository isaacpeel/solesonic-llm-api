package com.solesonic.config.openai;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The two enrichment models the ETL pipeline runs over every chunk it ingests, both served by an
 * OpenAI-compatible server pointed at by {@code solesonic.llm.etl.openai.host}. Configured
 * separately from chat — its own host, model and timeout — so enrichment can run on its own
 * hardware, exactly as {@link VisionOpenAiConfig} does for image description.
 * <p>
 * Both beans share a host and a model and differ only in sampling: keyword extraction wants a
 * deterministic short answer, metadata summarisation a longer and slightly freer one.
 * <p>
 * There is nothing here to pull a model on demand, keep it resident after an idle period, or size
 * its context per request: an OpenAI-compatible server loads one model at process start, and
 * {@code --ctx-size}/{@code --batch-size} are server-launch flags, not per-request options. Whoever
 * runs the ETL server has to have launched it with a context large enough for a chunk plus its
 * answer.
 * <p>
 * {@code repeatPenalty}, {@code topK} and {@code minP} are absent for a different reason: OpenAI's
 * {@code frequency_penalty} is an additive penalty on a different scale to a multiplicative
 * {@code repeat_penalty} — carrying the number across would change sampling rather than preserve
 * it — and the other two are dropped from the request by Spring AI's OpenAI model entirely.
 */
@Configuration
public class EtlOpenAiConfig {
    public static final String ETL_KEYWORD_CHAT_MODEL = "ETL_KEYWORD_CHAT_MODEL";
    public static final String ETL_METADATA_CHAT_MODEL = "ETL_METADATA_CHAT_MODEL";

    /**
     * {@code defaultCandidate = false} is required, not stylistic: an unqualified
     * {@link OpenAiChatModel} injection point would otherwise see this bean as an ambiguous
     * candidate alongside every other hand-built model in {@code config/openai}. Inject by
     * {@link Qualifier} only.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(ETL_KEYWORD_CHAT_MODEL)
    public OpenAiChatModel etlKeywordChatModel(@Value("${spring.ai.openai.api-key}") String apiKey,
                                               @Value("${solesonic.llm.etl.model}") String etlModel,
                                               @Value("${solesonic.llm.etl.openai.read-timeout}") Duration readTimeout) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .model(etlModel)
                .timeout(readTimeout)
                .temperature(0.0)
                .seed(42)
                .maxTokens(64)
                .build();

        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }

    @Bean(defaultCandidate = false)
    @Qualifier(ETL_METADATA_CHAT_MODEL)
    public OpenAiChatModel etlMetadataChatModel(@Value("${spring.ai.openai.api-key}") String apiKey,
                                                @Value("${solesonic.llm.etl.model}") String etlModel,
                                                @Value("${solesonic.llm.etl.openai.read-timeout}") Duration readTimeout) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .model(etlModel)
                .timeout(readTimeout)
                .temperature(0.3)
                .topP(0.8)
                .seed(42)
                .maxTokens(256)
                .build();

        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }
}
