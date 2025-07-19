package com.solesonic.izzybot.service.ollama;

import com.solesonic.izzybot.model.training.DocumentStatus;
import com.solesonic.izzybot.model.training.StatusHistory;
import com.solesonic.izzybot.model.training.TrainingDocument;
import com.solesonic.izzybot.repository.ollama.StatusHistoryRepository;
import com.solesonic.izzybot.service.etl.DocumentService;
import com.solesonic.izzybot.service.rag.TrainingDocumentService;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class StatusHistoryService {
    private static final Logger log = LoggerFactory.getLogger(StatusHistoryService.class);

    private final StatusHistoryRepository statusHistoryRepository;
    private final DocumentService documentService;
    private final TrainingDocumentService trainingDocumentService;

    public StatusHistoryService(StatusHistoryRepository statusHistoryRepository,
                                DocumentService documentService,
                                TrainingDocumentService trainingDocumentService) {
        this.statusHistoryRepository = statusHistoryRepository;
        this.documentService = documentService;
        this.trainingDocumentService = trainingDocumentService;
    }

    public void processQueued() {
        List<StatusHistory> inProgress = statusHistoryRepository.findInProgress();

        log.debug("In progress: {}", inProgress.size());

        if (CollectionUtils.isEmpty(inProgress)) {
            List<StatusHistory> queuedDocuments = statusHistoryRepository.findQueued();
            log.debug("Documents queued: {}", queuedDocuments.size());
            if (!CollectionUtils.isEmpty(queuedDocuments)) {


                TrainingDocument confluenceTrainingDocument = null;

                for (StatusHistory status : queuedDocuments) {
                    try {
                        UUID documentId = status.getDocumentId();
                        log.debug("Processing {} document with id: {}", status.getDocumentStatus(), documentId);
                        confluenceTrainingDocument = trainingDocumentService.get(documentId);

                        trainingDocumentService.update(confluenceTrainingDocument, DocumentStatus.IN_PROGRESS);
                        documentService.resourceToVectorStore(status.getDocumentId());
                    } catch (Exception e) {
                        log.error("Document processing failed", e);
                        assert confluenceTrainingDocument != null;
                        trainingDocumentService.update(confluenceTrainingDocument, DocumentStatus.FAILED);
                    }
                }
            }
        }
    }
}
