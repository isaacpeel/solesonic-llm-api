package com.solesonic.config.olllama;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EtlOllamaConfig {
    public static final String ETL_CHAT_MODEL = "ETL_CHAT_MODEL";

    @Bean
    @Qualifier(ETL_CHAT_MODEL)
    public OllamaChatModel etlChatModel(@Value("${solesonic.llm.etl.ollama.host}") String ollamaBaseUrl,
                                        @Value("${solesonic.llm.etl.model}") String etlModel) {
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(ollamaBaseUrl)
                .build();

        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(etlModel)
                .build();

        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(options)
                .build();
    }
}