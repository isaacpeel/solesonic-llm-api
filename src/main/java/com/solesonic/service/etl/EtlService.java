package com.solesonic.service.etl;

import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.service.ingestion.IngestedDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EtlService {
    private static final Logger log = LoggerFactory.getLogger(EtlService.class);
    private final IngestedDocumentService ingestedDocumentService;
    private final EtlKeywordEnricher etlKeywordEnricher;
    private final EtlMetadataEnricher etlMetadataEnricher;
    private final EtlTextSplitter etlTextSplitter;

    public EtlService(IngestedDocumentService ingestedDocumentService,
                      EtlKeywordEnricher etlKeywordEnricher,
                      EtlMetadataEnricher etlMetadataEnricher,
                      EtlTextSplitter etlTextSplitter) {
        this.ingestedDocumentService = ingestedDocumentService;
        this.etlKeywordEnricher = etlKeywordEnricher;
        this.etlMetadataEnricher = etlMetadataEnricher;
        this.etlTextSplitter = etlTextSplitter;
    }

    public List<Document> prepare(List<Document> documents) {
        List<Document> splitDocuments = etlTextSplitter.split(documents);
        List<Document> keywordEnriched = etlKeywordEnricher.enrich(splitDocuments);

        return etlMetadataEnricher.enrich(keywordEnriched);
    }

    public List<Document> prepare(List<Document> documents, IngestedDocument ingestedDocument) {
        log.info("Preparing documents");
        ingestedDocumentService.update(ingestedDocument, DocumentStatus.PREPARING);

        log.info("Token splitting documents");
        ingestedDocumentService.update(ingestedDocument, DocumentStatus.TOKEN_SPLITTING);
        List<Document> splitDocuments = etlTextSplitter.split(documents);

        log.info("Keyword enriching documents");
        ingestedDocumentService.update(ingestedDocument, DocumentStatus.KEYWORD_ENRICHING);
        List<Document> keywordEnriched = etlKeywordEnricher.enrich(splitDocuments);

        log.info("Metadata enriching documents");
        ingestedDocumentService.update(ingestedDocument, DocumentStatus.METADATA_ENRICHING);
        return etlMetadataEnricher.enrich(keywordEnriched);
    }
}
