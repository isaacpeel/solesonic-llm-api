package com.solesonic.service.etl;

import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.solesonic.config.olllama.EtlOllamaConfig.ETL_KEYWORD_CHAT_MODEL;

@Component
public class EtlKeywordEnricher {
    private final OllamaChatModel chatModel;

    public EtlKeywordEnricher(@Qualifier(ETL_KEYWORD_CHAT_MODEL) OllamaChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public List<Document> enrich(List<Document> documents) {
        KeywordMetadataEnricher enricher = new KeywordMetadataEnricher(chatModel, 5);
        return enricher.apply(documents);
    }
}
