package com.solesonic.api.document;

import com.solesonic.model.VectorSearch;
import com.solesonic.model.document.UriIngestRequest;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.service.ingestion.IngestedDocumentService;
import com.solesonic.service.ingestion.UriIngestionService;
import com.solesonic.service.rag.VectorStoreService;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final VectorStoreService vectorStoreService;
    private final IngestedDocumentService ingestedDocumentService;
    private final UriIngestionService uriIngestionService;

    public DocumentController(VectorStoreService vectorStoreService,
                              IngestedDocumentService ingestedDocumentService,
                              UriIngestionService uriIngestionService) {
        this.vectorStoreService = vectorStoreService;
        this.ingestedDocumentService = ingestedDocumentService;
        this.uriIngestionService = uriIngestionService;
    }

    @PostMapping("/data/search")
    public ResponseEntity<List<String>> search(@RequestBody VectorSearch vectorSearch) {
        List<Document> similarDocuments =  vectorStoreService.findSimilarDocuments(vectorSearch);

        return ResponseEntity.ok().body(similarDocuments.stream().map(Document::getText).toList());
    }

    @PostMapping("/data/upload")
    public ResponseEntity<Void> handleFileUpload(@RequestParam MultipartFile file,
                                                 @RequestParam(required = false) RetrievalScope scope) {
        IngestedDocument ingestedDocument = ingestedDocumentService.queue(file, scope);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(ingestedDocument.getId())
                .toUri();

        return ResponseEntity.created(location).build();
    }

    @PostMapping("/uri")
    public ResponseEntity<IngestedDocument> handleUriIngest(@RequestBody UriIngestRequest uriIngestRequest) {
        IngestedDocument ingestedDocument = uriIngestionService.queue(uriIngestRequest.uri());

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/documents/ingested/{id}")
                .buildAndExpand(ingestedDocument.getId())
                .toUri();

        return ResponseEntity.accepted().location(location).body(ingestedDocument);
    }
}
