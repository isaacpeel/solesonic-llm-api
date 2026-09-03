package com.solesonic.api.document;

import com.solesonic.model.VectorSearch;
import com.solesonic.service.ingestion.StatusHistoryService;
import com.solesonic.service.rag.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What is left under {@code /documents} once the two scoped collections own creation and CRUD:
 * a search across whatever the caller can retrieve, and the operator's handle on the ingestion
 * queue. Neither is a document resource, which is why neither lives under
 * {@code /documents/global} or {@code /users/{userId}/documents}.
 */
@RestController
@RequestMapping("/documents")
public class DocumentController {
    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final VectorStoreService vectorStoreService;
    private final StatusHistoryService statusHistoryService;

    public DocumentController(VectorStoreService vectorStoreService,
                              StatusHistoryService statusHistoryService) {
        this.vectorStoreService = vectorStoreService;
        this.statusHistoryService = statusHistoryService;
    }

    @PostMapping("/data/search")
    public ResponseEntity<List<String>> search(@RequestBody VectorSearch vectorSearch) {
        List<Document> similarDocuments = vectorStoreService.findSimilarDocuments(vectorSearch);

        return ResponseEntity.ok().body(similarDocuments.stream().map(Document::getText).toList());
    }

    /**
     * Runs the ingestion queue now rather than waiting for {@code DocumentIngestionSchedulingTask}.
     * <p>
     * Carries no id and belongs to no collection — it drains whatever is queued, across both scopes.
     * {@code rag-admin}, for the same reason the shared collection's writes are: the work it starts
     * is chunking and embedding against the ETL model, so an ungated handle on it is an ungated
     * handle on that load.
     */
    @PreAuthorize("hasRole('rag-admin')")
    @PostMapping("/processQueue")
    public ResponseEntity<Void> processQueue() {
        log.info("Processing ingestion queue");
        statusHistoryService.processQueued();

        return ResponseEntity.accepted().build();
    }
}
