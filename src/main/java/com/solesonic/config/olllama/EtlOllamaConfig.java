package com.solesonic.config.olllama;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.springframework.ai.ollama.management.PullModelStrategy.WHEN_MISSING;

@Configuration
public class EtlOllamaConfig {
    public static final String ETL_KEYWORD_CHAT_MODEL = "ETL_KEYWORD_CHAT_MODEL";
    public static final String ETL_METADATA_CHAT_MODEL = "ETL_METADATA_CHAT_MODEL";

    @Bean(defaultCandidate = false)
    @Qualifier(ETL_KEYWORD_CHAT_MODEL)
    public OllamaChatModel etlKeywordChatModel(@Value("${solesonic.llm.etl.ollama.host}") String ollamaBaseUrl,
                                               @Value("${solesonic.llm.etl.model}") String etlModel) {
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(ollamaBaseUrl)
                .build();

        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(etlModel)
                .numCtx(2048)
                .numBatch(512)
                .keepAlive("30m")
                .temperature(0.0)
                .repeatPenalty(1.1)
                .seed(42)
                .numPredict(64)
                .disableThinking()
                .build();

        ModelManagementOptions modelManagementOptions = ModelManagementOptions.builder()
                .pullModelStrategy(WHEN_MISSING)
                .build();

        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(options)
                .modelManagementOptions(modelManagementOptions)
                .build();
    }

    @Bean(defaultCandidate = false)
    @Qualifier(ETL_METADATA_CHAT_MODEL)
    public OllamaChatModel etlMetadataChatModel(@Value("${solesonic.llm.etl.ollama.host}") String ollamaBaseUrl,
                                                @Value("${solesonic.llm.etl.model}") String etlModel) {
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(ollamaBaseUrl)
                .build();

        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(etlModel)
                .numCtx(2048)
                .numBatch(512)
                .keepAlive("30m")
                .temperature(0.3)
                .topP(0.8)
                .topK(20)
                .minP(0.0)
                .repeatPenalty(1.05)
                .seed(42)
                .numPredict(256)
                .disableThinking()
                .build();

        ModelManagementOptions modelManagementOptions = ModelManagementOptions.builder()
                .pullModelStrategy(WHEN_MISSING)
                .build();

        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(options)
                .modelManagementOptions(modelManagementOptions)
                .build();
    }
}
