package com.solesonic.service.etl;

import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EtlKeywordEnricher {
    private final OllamaChatModel chatModel;

    public EtlKeywordEnricher(OllamaChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public List<Document> enrich(List<Document> documents) {
        KeywordMetadataEnricher enricher = new KeywordMetadataEnricher(chatModel, 5);
        return enricher.apply(documents);
    }
}