package com.solesonic.service.rag;

import com.solesonic.exception.ChatException;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.training.DocumentStatus;
import com.solesonic.model.training.StatusHistory;
import com.solesonic.model.training.TrainingDocument;
import com.solesonic.model.training.VectorDocument;
import com.solesonic.repository.ollama.StatusHistoryRepository;
import com.solesonic.repository.ollama.TrainingDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.solesonic.model.training.DocumentStatus.FAILED;

@Service
public class TrainingDocumentService {
    private static final Logger log = LoggerFactory.getLogger(TrainingDocumentService.class);
    private final TrainingDocumentRepository trainingDocumentRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final VectorStoreService vectorStoreService;

    public TrainingDocumentService(TrainingDocumentRepository trainingDocumentRepository,
                                   StatusHistoryRepository statusHistoryRepository,
                                   VectorStoreService vectorStoreService) {
        this.trainingDocumentRepository = trainingDocumentRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.vectorStoreService = vectorStoreService;
    }

    public List<TrainingDocument> findAll() {
        List<TrainingDocument> trainingDocuments = new java.util.ArrayList<>(trainingDocumentRepository.findAllWithoutContent()
                .orElse(List.of()));

        for(TrainingDocument trainingDocument : trainingDocuments) {
            List<DocumentStatus> documentStatuses = statusHistoryRepository.findByDocumentId(trainingDocument.getId());
            trainingDocument.setDocumentStatus(documentStatuses.stream().findFirst().orElse(FAILED));
        }

        trainingDocuments.sort(Comparator.comparingInt(trainingDocument ->
                trainingDocument.getDocumentStatus() != null ? trainingDocument.getDocumentStatus().ordinal() : Integer.MAX_VALUE));

        return trainingDocuments;
    }

    public TrainingDocument save(TrainingDocument trainingDocument) {
        trainingDocument.setCreated(ZonedDateTime.now());
        trainingDocument.setUpdated(ZonedDateTime.now());

        trainingDocument =  trainingDocumentRepository.save(trainingDocument);

        StatusHistory statusHistory = new StatusHistory();
        statusHistory.setDocumentStatus(trainingDocument.getDocumentStatus());
        statusHistory.setDocumentId(trainingDocument.getId());
        statusHistory.setTimestamp(ZonedDateTime.now());

        statusHistoryRepository.save(statusHistory);

        return trainingDocument;
    }

    public TrainingDocument  update(TrainingDocument trainingDocument, DocumentStatus documentStatus) {
        log.info("Updating training document: {}", trainingDocument.getId());
        trainingDocument.setUpdated(ZonedDateTime.now());

        if(!documentStatus.equals(trainingDocument.getDocumentStatus())) {
            log.info("Updating document id: {} to status: {}", trainingDocument.getId(), documentStatus);

            StatusHistory statusHistory = new StatusHistory();
            statusHistory.setDocumentStatus(documentStatus);
            statusHistory.setDocumentId(trainingDocument.getId());
            statusHistory.setTimestamp(ZonedDateTime.now());

            statusHistoryRepository.save(statusHistory);

            trainingDocument.setDocumentStatus(documentStatus);
        }

        return trainingDocumentRepository.save(trainingDocument);
    }

    public TrainingDocument get(UUID documentId) {
        log.info("Getting document id: {}", documentId);
        TrainingDocument trainingDocument = trainingDocumentRepository.findById(documentId).orElseThrow(() -> new ChatException("Error getting training document"));

        List<DocumentStatus> documentStatuses = statusHistoryRepository.findByDocumentId(trainingDocument.getId());
        trainingDocument.setDocumentStatus(documentStatuses.stream().findFirst().orElse(null));

        return trainingDocument;
    }

    public TrainingDocument findByName(String fileName) {
        log.info("Getting document by name: {}", fileName);
        return trainingDocumentRepository.findByFileName(fileName)
                .orElse(null);
    }

    @Transactional
    public List<TrainingDocument> findByConfluencePageId(String confluencePageId) {
        log.debug("Finding training documents by confluence page id: {}", confluencePageId);

        return trainingDocumentRepository.findByConfluenceId(confluencePageId)
                .orElse(null);
    }

    @Transactional
    public List<String> findConfluencePageIds() {
        log.debug("Finding all tracked confluence page ids.");

        return trainingDocumentRepository.findConfluencePageIds();
    }

    @Transactional
    public List<TrainingDocument> findBySourceUri(String sourceUri) {
        log.debug("Finding training documents by source uri: {}", sourceUri);

        return trainingDocumentRepository.findBySourceUri(sourceUri)
                .orElse(List.of());
    }

    @Transactional
    public TrainingDocument refresh(UUID documentId) {
        log.info("Refreshing training document id: {}", documentId);

        TrainingDocument trainingDocument = trainingDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ChatException("Error getting training document"));

        backfillMetadata(trainingDocument);

        List<VectorDocument> vectorDocuments = vectorStoreService.findByTrainingDocumentId(documentId);
        vectorStoreService.delete(vectorDocuments);

        return update(trainingDocument, DocumentStatus.QUEUED);
    }

    private static void backfillMetadata(TrainingDocument trainingDocument) {
        if (trainingDocument.getDocumentSource() == null) {
            trainingDocument.setDocumentSource(DocumentSource.USER);
        }

        if (trainingDocument.getMetadata() == null) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(TrainingDocument.ORIGINAL_FILE_NAME, trainingDocument.getFileName());
            metadata.put(TrainingDocument.FILE_SIZE_BYTES, trainingDocument.getFileData().length);
            trainingDocument.setMetadata(metadata);
        }
    }

    @Transactional
    public void delete(UUID documentId) {
        log.info("Deleting training document id: {}", documentId);

        TrainingDocument trainingDocument = trainingDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ChatException("Error getting training document"));

        List<VectorDocument> vectorDocuments = vectorStoreService.findByTrainingDocumentId(documentId);
        vectorStoreService.delete(vectorDocuments);

        delete(trainingDocument);
    }

    @Transactional
    public void delete(TrainingDocument trainingDocument) {
        log.info("Deleting training document: {}", trainingDocument.getId());

        statusHistoryRepository.deleteByDocumentId(trainingDocument.getId());
        trainingDocumentRepository.delete(trainingDocument);
    }

    public TrainingDocument queue(MultipartFile multipartFile) {
        log.debug("Queuing document.");

        Resource newFileResource = multipartFile.getResource();

        String fileName = newFileResource.getFilename();

        TrainingDocument existing = findByName(fileName);

        if(existing != null) {
            return existing;
        }

        TrainingDocument trainingDocument = trainingDocument(multipartFile, fileName, newFileResource);

        return save(trainingDocument);
    }

    private static TrainingDocument trainingDocument(MultipartFile multipartFile, String fileName, Resource newFileResource) {
        String contentType = multipartFile.getContentType();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(TrainingDocument.ORIGINAL_FILE_NAME, fileName);
        metadata.put(TrainingDocument.FILE_SIZE_BYTES, multipartFile.getSize());

        TrainingDocument trainingDocument = new TrainingDocument();
        trainingDocument.setDocumentStatus(DocumentStatus.QUEUED);
        trainingDocument.setFileName(fileName);
        trainingDocument.setContentType(contentType);
        trainingDocument.setDocumentSource(DocumentSource.USER);
        trainingDocument.setMetadata(metadata);

        try (InputStream inputStream = newFileResource.getInputStream()) {
            byte[] fileContent = inputStream.readAllBytes();
            trainingDocument.setFileData(fileContent);
        } catch (IOException e) {
            throw new ChatException("Failed to upload document", e);
        }

        return trainingDocument;
    }
}
