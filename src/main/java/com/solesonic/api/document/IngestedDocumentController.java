package com.solesonic.api.document;

import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.service.ingestion.IngestedDocumentService;
import com.solesonic.service.ingestion.StatusHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("")
public class IngestedDocumentController {
    private static final Logger log = LoggerFactory.getLogger(IngestedDocumentController.class);

    private final IngestedDocumentService ingestedDocumentService;
    private final StatusHistoryService statusHistoryService;

    public IngestedDocumentController(IngestedDocumentService ingestedDocumentService,
                                      StatusHistoryService statusHistoryService) {
        this.ingestedDocumentService = ingestedDocumentService;
        this.statusHistoryService = statusHistoryService;
    }

    @GetMapping("/documents/ingested")
    public ResponseEntity<List<IngestedDocument>> findAllIngestedDocuments() {
        log.info("Finding all ingested documents");
        List<IngestedDocument> ingestedDocuments = this.ingestedDocumentService.findAll();

        return ResponseEntity.ok(ingestedDocuments);
    }

    @PostMapping("/documents/ingested/{id}/refresh")
    public ResponseEntity<IngestedDocument> refreshIngestedDocument(@PathVariable UUID id) {
        log.info("Refreshing ingested document id: {}", id);
        IngestedDocument ingestedDocument = this.ingestedDocumentService.refresh(id);

        return ResponseEntity.accepted().body(ingestedDocument);
    }

    @PostMapping("/documents/ingested/processQueue")
    public ResponseEntity<Void> processQueue() {
        log.info("Processing Queue");
        statusHistoryService.processQueued();

        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/documents/ingested/{id}")
    public ResponseEntity<Void> deleteIngestedDocument(@PathVariable UUID id) {
        log.info("Deleting ingested document id: {}", id);
        this.ingestedDocumentService.delete(id);

        return ResponseEntity.noContent().build();
    }
}
