package com.solesonic.api.ollama;

import com.solesonic.model.training.TrainingDocument;
import com.solesonic.service.ollama.StatusHistoryService;
import com.solesonic.service.rag.TrainingDocumentService;
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
public class TrainingDocumentController {
    private static final Logger log = LoggerFactory.getLogger(TrainingDocumentController.class);

    private final TrainingDocumentService trainingDocumentService;
    private final StatusHistoryService statusHistoryService;

    public TrainingDocumentController(TrainingDocumentService trainingDocumentService,
                                      StatusHistoryService statusHistoryService) {
        this.trainingDocumentService = trainingDocumentService;
        this.statusHistoryService = statusHistoryService;
    }

    @GetMapping("/trainingdocuments")
    public ResponseEntity<List<TrainingDocument>> findAllTrainingDocuments() {
        log.info("Finding all training documents");
        List<TrainingDocument> trainingDocuments = this.trainingDocumentService.findAll();

        return ResponseEntity.ok(trainingDocuments);
    }

    @PostMapping("/trainingdocuments/{id}/refresh")
    public ResponseEntity<TrainingDocument> refreshTrainingDocument(@PathVariable UUID id) {
        log.info("Refreshing training document id: {}", id);
        TrainingDocument trainingDocument = this.trainingDocumentService.refresh(id);

        return ResponseEntity.accepted().body(trainingDocument);
    }

    @PostMapping("/trainingdocuments/processQueue")
    public ResponseEntity<Void> processQueue() {
        log.info("Processing Queue");
        statusHistoryService.processQueued();

        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/trainingdocuments/{id}")
    public ResponseEntity<Void> deleteTrainingDocument(@PathVariable UUID id) {
        log.info("Deleting training document id: {}", id);
        this.trainingDocumentService.delete(id);

        return ResponseEntity.noContent().build();
    }
}
