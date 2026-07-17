package com.solesonic.service.etl;

import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.SummaryMetadataEnricher;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.solesonic.config.olllama.EtlOllamaConfig.ETL_CHAT_MODEL;

@Component
public class EtlMetadataEnricher {
    private final OllamaChatModel chatModel;

    public EtlMetadataEnricher(@Qualifier(ETL_CHAT_MODEL) OllamaChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public List<Document> enrich(List<Document> documents) {
        SummaryMetadataEnricher summaryMetadataEnricher = new SummaryMetadataEnricher(chatModel,
            List.of(SummaryMetadataEnricher.SummaryType.PREVIOUS, 
                   SummaryMetadataEnricher.SummaryType.CURRENT, 
                   SummaryMetadataEnricher.SummaryType.NEXT));
        return summaryMetadataEnricher.apply(documents);
    }
}