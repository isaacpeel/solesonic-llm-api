package com.solesonic.tools.rag;

import com.solesonic.model.training.DocumentStatus;
import com.solesonic.model.training.TrainingDocument;
import com.solesonic.service.rag.UriTrainingService;
import com.solesonic.tools.LocalTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UriIngestTools implements LocalTool {
    private static final Logger log = LoggerFactory.getLogger(UriIngestTools.class);

    public static final String URI_INGEST = "uri-ingest";

    private final UriTrainingService uriTrainingService;

    public UriIngestTools(UriTrainingService uriTrainingService) {
        this.uriTrainingService = uriTrainingService;
    }

    public record UriIngestRequest(String uri) {}
    public record UriIngestResponse(UUID trainingDocumentId, String uri, DocumentStatus documentStatus) {}

    @SuppressWarnings("unused")
    @Tool(name = URI_INGEST, description = "Adds a uri to the RAG ingestion queue so its content can be fetched and embedded into the vector store. Use responsibly and ensure no repeated calls for the same uri.")
    @PreAuthorize("hasAuthority('ROLE_rag-admin')")
    public UriIngestResponse uriIngest(UriIngestRequest request) {
        log.debug("Invoking uri ingest tool");
        log.debug("Uri: {}", request.uri);

        TrainingDocument trainingDocument = uriTrainingService.queue(request.uri);

        log.debug("Queued training document: {}", trainingDocument.getId());

        return new UriIngestResponse(trainingDocument.getId(), request.uri, trainingDocument.getDocumentStatus());
    }
}
