package com.solesonic.izzybot.api.ollama;

import com.solesonic.izzybot.model.training.TrainingDocument;
import com.solesonic.izzybot.service.rag.TrainingDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("")
public class TrainingDocumentController {
    private static final Logger log = LoggerFactory.getLogger(TrainingDocumentController.class);

    private final TrainingDocumentService trainingDocumentService;

    public TrainingDocumentController(TrainingDocumentService trainingDocumentService) {
        this.trainingDocumentService = trainingDocumentService;
    }

    @GetMapping("/trainingdocuments")
    public ResponseEntity<List<TrainingDocument>> findAllTrainingDocuments() {
        log.info("Finding all training documents");
        List<TrainingDocument> trainingDocuments = this.trainingDocumentService.findAll();

        return ResponseEntity.ok(trainingDocuments);
    }
}
