package com.solesonic.api.ollama;

import com.solesonic.model.VectorSearch;
import com.solesonic.model.document.UriIngestRequest;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.model.training.TrainingDocument;
import com.solesonic.service.rag.TrainingDocumentService;
import com.solesonic.service.rag.UriTrainingService;
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
    private final TrainingDocumentService trainingDocumentService;
    private final UriTrainingService uriTrainingService;

    public DocumentController(VectorStoreService vectorStoreService,
                              TrainingDocumentService trainingDocumentService,
                              UriTrainingService uriTrainingService) {
        this.vectorStoreService = vectorStoreService;
        this.trainingDocumentService = trainingDocumentService;
        this.uriTrainingService = uriTrainingService;
    }

    @PostMapping("/data/search")
    public ResponseEntity<List<String>> search(@RequestBody VectorSearch vectorSearch) {
        List<Document> similarDocuments =  vectorStoreService.findSimilarDocuments(vectorSearch);

        return ResponseEntity.ok().body(similarDocuments.stream().map(Document::getText).toList());
    }

    
    @PostMapping("/data/upload")
    public ResponseEntity<Void> handleFileUpload(@RequestParam MultipartFile file,
                                                 @RequestParam(required = false) RetrievalScope scope) {
        TrainingDocument trainingDocument = trainingDocumentService.queue(file, scope);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(trainingDocument.getId())
                .toUri();

        return ResponseEntity.created(location).build();
    }

    @PostMapping("/uri")
    public ResponseEntity<TrainingDocument> handleUriIngest(@RequestBody UriIngestRequest uriIngestRequest) {
        TrainingDocument trainingDocument = uriTrainingService.queue(uriIngestRequest.uri());

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/trainingdocuments/{id}")
                .buildAndExpand(trainingDocument.getId())
                .toUri();

        return ResponseEntity.accepted().location(location).body(trainingDocument);
    }
}
