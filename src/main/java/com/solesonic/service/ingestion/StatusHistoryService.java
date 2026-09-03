package com.solesonic.service.ingestion;

import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.StatusHistory;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.repository.ingestion.StatusHistoryRepository;
import com.solesonic.service.etl.DocumentService;
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
    private final IngestedDocumentService ingestedDocumentService;

    public StatusHistoryService(StatusHistoryRepository statusHistoryRepository,
                                DocumentService documentService,
                                IngestedDocumentService ingestedDocumentService) {
        this.statusHistoryRepository = statusHistoryRepository;
        this.documentService = documentService;
        this.ingestedDocumentService = ingestedDocumentService;
    }

    public void processQueued() {
        List<StatusHistory> inProgress = statusHistoryRepository.findInProgress();

        log.debug("In progress: {}", inProgress.size());

        if (CollectionUtils.isEmpty(inProgress)) {
            List<StatusHistory> queuedDocuments = statusHistoryRepository.findQueued();
            log.debug("Documents queued: {}", queuedDocuments.size());
            if (!CollectionUtils.isEmpty(queuedDocuments)) {

                IngestedDocument confluenceIngestedDocument = null;

                for (StatusHistory status : queuedDocuments) {
                    try {
                        UUID documentId = status.getDocumentId();
                        log.debug("Processing {} document with id: {}", status.getDocumentStatus(), documentId);
                        confluenceIngestedDocument = ingestedDocumentService.get(documentId);

                        ingestedDocumentService.update(confluenceIngestedDocument, DocumentStatus.IN_PROGRESS);
                        documentService.resourceToVectorStore(status.getDocumentId());
                    } catch (Exception e) {
                        log.error("Document processing failed", e);
                        assert confluenceIngestedDocument != null;
                        ingestedDocumentService.update(confluenceIngestedDocument, DocumentStatus.FAILED);
                    }
                }
            }
        }
    }
}
