package com.solesonic.izzybot.service.etl;

import com.solesonic.izzybot.model.training.DocumentStatus;
import com.solesonic.izzybot.model.training.TrainingDocument;
import com.solesonic.izzybot.service.rag.TrainingDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EtlService {
    private static final Logger log = LoggerFactory.getLogger(EtlService.class);
    private final TrainingDocumentService trainingDocumentService;
    private final EtlKeywordEnricher etlKeywordEnricher;
    private final EtlMetadataEnricher etlMetadataEnricher;
    private final EtlTextSplitter etlTextSplitter;

    public EtlService(TrainingDocumentService trainingDocumentService,
                      EtlKeywordEnricher etlKeywordEnricher,
                      EtlMetadataEnricher etlMetadataEnricher,
                      EtlTextSplitter etlTextSplitter) {
        this.trainingDocumentService = trainingDocumentService;
        this.etlKeywordEnricher = etlKeywordEnricher;
        this.etlMetadataEnricher = etlMetadataEnricher;
        this.etlTextSplitter = etlTextSplitter;
    }

    public List<Document> prepare(List<Document> documents, TrainingDocument trainingDocument) {
        log.info("Preparing documents");
        trainingDocumentService.update(trainingDocument, DocumentStatus.PREPARING);

        log.info("Keyword enriching documents");
        trainingDocumentService.update(trainingDocument, DocumentStatus.KEYWORD_ENRICHING);
        List<Document> keywordEnriched = etlKeywordEnricher.enrich(documents);

        log.info("Metadata enriched documents");
        trainingDocumentService.update(trainingDocument, DocumentStatus.METADATA_ENRICHING);
        List<Document> metadataEnriched = etlMetadataEnricher.enrich(keywordEnriched);

        log.info("Token splitting documents");
        trainingDocumentService.update(trainingDocument, DocumentStatus.TOKEN_SPLITTING);

        return etlTextSplitter.split(metadataEnriched);
    }
}
