package com.solesonic.izzybot.api.ollama;

import com.solesonic.izzybot.model.VectorSearch;
import com.solesonic.izzybot.model.training.TrainingDocument;
import com.solesonic.izzybot.service.rag.TrainingDocumentService;
import com.solesonic.izzybot.service.rag.VectorStoreService;
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

    public DocumentController(VectorStoreService vectorStoreService,
                              TrainingDocumentService trainingDocumentService) {
        this.vectorStoreService = vectorStoreService;
        this.trainingDocumentService = trainingDocumentService;
    }

    @PostMapping("/data/search")
    public ResponseEntity<List<String>> search(@RequestBody VectorSearch vectorSearch) {
        List<Document> similarDocuments =  vectorStoreService.findSimilarDocuments(vectorSearch);

        return ResponseEntity.ok().body(similarDocuments.stream().map(Document::getText).toList());
    }

    
    @PostMapping("/data/upload")
    public ResponseEntity<Void> handleFileUpload(@RequestParam MultipartFile file) {
        TrainingDocument trainingDocument = trainingDocumentService.queue(file);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(trainingDocument.getId())
                .toUri();

        return ResponseEntity.created(location).build();
    }
}
