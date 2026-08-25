package com.solesonic.service.rag;

import com.solesonic.exception.ChatException;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.model.training.DocumentStatus;
import com.solesonic.model.training.StatusHistory;
import com.solesonic.model.training.TrainingDocument;
import com.solesonic.model.training.VectorDocument;
import com.solesonic.repository.ollama.StatusHistoryRepository;
import com.solesonic.repository.ollama.TrainingDocumentRepository;
import com.solesonic.scope.UserRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

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
    private final UserRequestContext userRequestContext;

    public TrainingDocumentService(TrainingDocumentRepository trainingDocumentRepository,
                                   StatusHistoryRepository statusHistoryRepository,
                                   VectorStoreService vectorStoreService,
                                   UserRequestContext userRequestContext) {
        this.trainingDocumentRepository = trainingDocumentRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.vectorStoreService = vectorStoreService;
        this.userRequestContext = userRequestContext;
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

    /**
     * Queues an uploaded document for ingestion at the requested scope.
     * <p>
     * {@code scope} may be null, which means {@code GLOBAL} — the only behaviour this method had
     * before scoping existed.
     * <p>
     * Reads {@link UserRequestContext}, so this is only callable on a request thread. That is where
     * its one caller is; the URI ingest path, which also runs from a tool call with no request
     * bound, deliberately does not come through here.
     */
    public TrainingDocument queue(MultipartFile multipartFile, RetrievalScope scope) {
        RetrievalScope requestedScope = scope == null ? RetrievalScope.GLOBAL : scope;

        if (requestedScope == RetrievalScope.CHAT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Documents cannot be trained at CHAT scope; attach them to a message instead");
        }

        log.debug("Queuing document at {} scope.", requestedScope);

        Resource newFileResource = multipartFile.getResource();

        String fileName = newFileResource.getFilename();

        //Deduplication by file name is only safe between shared documents. Two users uploading
        //"notes.pdf" mean two different documents, and reusing one row for both would hand the
        //second uploader the first one's.
        if (requestedScope == RetrievalScope.GLOBAL) {
            TrainingDocument existing = findByName(fileName);

            if (existing != null && existing.getScope() != RetrievalScope.USER) {
                return existing;
            }
        }

        TrainingDocument trainingDocument = trainingDocument(multipartFile, fileName, newFileResource);

        trainingDocument.setScope(requestedScope);

        if (requestedScope == RetrievalScope.USER) {
            trainingDocument.setUserId(userRequestContext.getUserId());
        }

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
