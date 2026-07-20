package com.solesonic.service.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

import static com.solesonic.config.olllama.EtlOllamaConfig.ETL_KEYWORD_CHAT_MODEL;
import static com.solesonic.config.olllama.EtlOllamaConfig.ETL_METADATA_CHAT_MODEL;

/**
 * Preloads the ETL chat models on startup so the first scheduled enrichment call does not pay the
 * cold model-load cost. Because each model is configured with a keep-alive, a single warm-up request
 * keeps the model resident in Ollama, avoiding read timeouts on the initial keyword-enrichment call.
 */
@Component
public class EtlModelWarmup {
    private static final Logger log = LoggerFactory.getLogger(EtlModelWarmup.class);
    private static final String WARMUP_PROMPT = "ok";

    private final OllamaChatModel keywordChatModel;
    private final OllamaChatModel metadataChatModel;
    private final boolean warmupOnStartup;

    public EtlModelWarmup(@Qualifier(ETL_KEYWORD_CHAT_MODEL) OllamaChatModel keywordChatModel,
                          @Qualifier(ETL_METADATA_CHAT_MODEL) OllamaChatModel metadataChatModel,
                          @Value("${solesonic.llm.etl.ollama.warmup-on-startup:true}") boolean warmupOnStartup) {
        this.keywordChatModel = keywordChatModel;
        this.metadataChatModel = metadataChatModel;
        this.warmupOnStartup = warmupOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupModelsOnStartup() {
        if (!warmupOnStartup) {
            return;
        }

        // The keyword and metadata models resolve to the same underlying Ollama model on the same host, so
        // warm them up sequentially on a single thread. The first request absorbs the cold model-load cost;
        // the second finds the model already resident (held by its keep-alive). Loading them concurrently
        // would issue two simultaneous cold loads of the same large model and invite read timeouts.
        CompletableFuture.runAsync(() -> {
            warmup("keyword", keywordChatModel);
            warmup("metadata", metadataChatModel);
        });
    }

    private void warmup(String modelPurpose, OllamaChatModel chatModel) {
        try {
            log.info("Warming up ETL {} chat model", modelPurpose);
            chatModel.call(new Prompt(WARMUP_PROMPT));
            log.info("ETL {} chat model warmed up and kept alive", modelPurpose);
        } catch (RuntimeException exception) {
            log.warn("Failed to warm up ETL {} chat model: {}", modelPurpose, exception.getMessage());
        }
    }
}