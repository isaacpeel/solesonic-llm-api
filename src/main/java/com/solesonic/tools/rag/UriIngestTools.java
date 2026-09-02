package com.solesonic.tools.rag;

import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.service.ingestion.UriIngestionService;
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

    private final UriIngestionService uriIngestionService;

    public UriIngestTools(UriIngestionService uriIngestionService) {
        this.uriIngestionService = uriIngestionService;
    }

    public record UriIngestRequest(String uri) {}
    public record UriIngestResponse(UUID ingestedDocumentId, String uri, DocumentStatus documentStatus) {}

    @SuppressWarnings("unused")
    @Tool(name = URI_INGEST, description = "Adds a uri to the RAG ingestion queue so its content can be fetched and embedded into the vector store. Use responsibly and ensure no repeated calls for the same uri.")
    @PreAuthorize("hasAuthority('ROLE_rag-admin')")
    public UriIngestResponse uriIngest(UriIngestRequest request) {
        log.debug("Invoking uri ingest tool");
        log.debug("Uri: {}", request.uri);

        IngestedDocument ingestedDocument = uriIngestionService.queue(request.uri);

        log.debug("Queued ingested document: {}", ingestedDocument.getId());

        return new UriIngestResponse(ingestedDocument.getId(), request.uri, ingestedDocument.getDocumentStatus());
    }
}
