package com.solesonic.service.ingestion;

import com.solesonic.exception.ChatException;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.StatusHistory;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.VectorDocument;
import com.solesonic.service.rag.VectorStoreService;
import com.solesonic.repository.ingestion.StatusHistoryRepository;
import com.solesonic.repository.ingestion.IngestedDocumentRepository;
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

import static com.solesonic.model.ingestion.DocumentStatus.FAILED;

@Service
public class IngestedDocumentService {
    private static final Logger log = LoggerFactory.getLogger(IngestedDocumentService.class);
    private final IngestedDocumentRepository ingestedDocumentRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final VectorStoreService vectorStoreService;
    private final UserRequestContext userRequestContext;

    public IngestedDocumentService(IngestedDocumentRepository ingestedDocumentRepository,
                                   StatusHistoryRepository statusHistoryRepository,
                                   VectorStoreService vectorStoreService,
                                   UserRequestContext userRequestContext) {
        this.ingestedDocumentRepository = ingestedDocumentRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.vectorStoreService = vectorStoreService;
        this.userRequestContext = userRequestContext;
    }

    public List<IngestedDocument> findAll() {
        List<IngestedDocument> ingestedDocuments = new java.util.ArrayList<>(ingestedDocumentRepository.findAllWithoutContent()
                .orElse(List.of()));

        for(IngestedDocument ingestedDocument : ingestedDocuments) {
            List<DocumentStatus> documentStatuses = statusHistoryRepository.findByDocumentId(ingestedDocument.getId());
            ingestedDocument.setDocumentStatus(documentStatuses.stream().findFirst().orElse(FAILED));
        }

        ingestedDocuments.sort(Comparator.comparingInt(ingestedDocument ->
                ingestedDocument.getDocumentStatus() != null ? ingestedDocument.getDocumentStatus().ordinal() : Integer.MAX_VALUE));

        return ingestedDocuments;
    }

    public IngestedDocument save(IngestedDocument ingestedDocument) {
        ingestedDocument.setCreated(ZonedDateTime.now());
        ingestedDocument.setUpdated(ZonedDateTime.now());

        ingestedDocument =  ingestedDocumentRepository.save(ingestedDocument);

        StatusHistory statusHistory = new StatusHistory();
        statusHistory.setDocumentStatus(ingestedDocument.getDocumentStatus());
        statusHistory.setDocumentId(ingestedDocument.getId());
        statusHistory.setTimestamp(ZonedDateTime.now());

        statusHistoryRepository.save(statusHistory);

        return ingestedDocument;
    }

    public IngestedDocument update(IngestedDocument ingestedDocument, DocumentStatus documentStatus) {
        log.info("Updating ingested document: {}", ingestedDocument.getId());
        ingestedDocument.setUpdated(ZonedDateTime.now());

        if(!documentStatus.equals(ingestedDocument.getDocumentStatus())) {
            log.info("Updating document id: {} to status: {}", ingestedDocument.getId(), documentStatus);

            StatusHistory statusHistory = new StatusHistory();
            statusHistory.setDocumentStatus(documentStatus);
            statusHistory.setDocumentId(ingestedDocument.getId());
            statusHistory.setTimestamp(ZonedDateTime.now());

            statusHistoryRepository.save(statusHistory);

            ingestedDocument.setDocumentStatus(documentStatus);
        }

        return ingestedDocumentRepository.save(ingestedDocument);
    }

    public IngestedDocument get(UUID documentId) {
        log.info("Getting document id: {}", documentId);
        IngestedDocument ingestedDocument = ingestedDocumentRepository.findById(documentId).orElseThrow(() -> new ChatException("Error getting ingested document"));

        List<DocumentStatus> documentStatuses = statusHistoryRepository.findByDocumentId(ingestedDocument.getId());
        ingestedDocument.setDocumentStatus(documentStatuses.stream().findFirst().orElse(null));

        return ingestedDocument;
    }

    public IngestedDocument findByName(String fileName) {
        log.info("Getting document by name: {}", fileName);
        return ingestedDocumentRepository.findByFileName(fileName)
                .orElse(null);
    }

    @Transactional
    public List<IngestedDocument> findByConfluencePageId(String confluencePageId) {
        log.debug("Finding ingested documents by confluence page id: {}", confluencePageId);

        return ingestedDocumentRepository.findByConfluenceId(confluencePageId)
                .orElse(null);
    }

    @Transactional
    public List<String> findConfluencePageIds() {
        log.debug("Finding all tracked confluence page ids.");

        return ingestedDocumentRepository.findConfluencePageIds();
    }

    @Transactional
    public List<IngestedDocument> findBySourceUri(String sourceUri) {
        log.debug("Finding ingested documents by source uri: {}", sourceUri);

        return ingestedDocumentRepository.findBySourceUri(sourceUri)
                .orElse(List.of());
    }

    @Transactional
    public IngestedDocument refresh(UUID documentId) {
        log.info("Refreshing ingested document id: {}", documentId);

        IngestedDocument ingestedDocument = ingestedDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ChatException("Error getting ingested document"));

        backfillMetadata(ingestedDocument);

        List<VectorDocument> vectorDocuments = vectorStoreService.findByIngestedDocumentId(documentId);
        vectorStoreService.delete(vectorDocuments);

        return update(ingestedDocument, DocumentStatus.QUEUED);
    }

    private static void backfillMetadata(IngestedDocument ingestedDocument) {
        if (ingestedDocument.getDocumentSource() == null) {
            ingestedDocument.setDocumentSource(DocumentSource.USER);
        }

        if (ingestedDocument.getMetadata() == null) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(IngestedDocument.ORIGINAL_FILE_NAME, ingestedDocument.getFileName());
            metadata.put(IngestedDocument.FILE_SIZE_BYTES, ingestedDocument.getFileData().length);
            ingestedDocument.setMetadata(metadata);
        }
    }

    @Transactional
    public void delete(UUID documentId) {
        log.info("Deleting ingested document id: {}", documentId);

        IngestedDocument ingestedDocument = ingestedDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ChatException("Error getting ingested document"));

        List<VectorDocument> vectorDocuments = vectorStoreService.findByIngestedDocumentId(documentId);
        vectorStoreService.delete(vectorDocuments);

        delete(ingestedDocument);
    }

    @Transactional
    public void delete(IngestedDocument ingestedDocument) {
        log.info("Deleting ingested document: {}", ingestedDocument.getId());

        statusHistoryRepository.deleteByDocumentId(ingestedDocument.getId());
        ingestedDocumentRepository.delete(ingestedDocument);
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
    public IngestedDocument queue(MultipartFile multipartFile, RetrievalScope scope) {
        RetrievalScope requestedScope = scope == null ? RetrievalScope.GLOBAL : scope;

        if (requestedScope == RetrievalScope.CHAT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Documents cannot be ingested at CHAT scope; attach them to a message instead");
        }

        log.debug("Queuing document at {} scope.", requestedScope);

        Resource newFileResource = multipartFile.getResource();

        String fileName = newFileResource.getFilename();

        //Deduplication by file name is only safe between shared documents. Two users uploading
        //"notes.pdf" mean two different documents, and reusing one row for both would hand the
        //second uploader the first one's.
        if (requestedScope == RetrievalScope.GLOBAL) {
            IngestedDocument existing = findByName(fileName);

            if (existing != null && existing.getScope() != RetrievalScope.USER) {
                return existing;
            }
        }

        IngestedDocument ingestedDocument = ingestedDocument(multipartFile, fileName, newFileResource);

        ingestedDocument.setScope(requestedScope);

        if (requestedScope == RetrievalScope.USER) {
            ingestedDocument.setUserId(userRequestContext.getUserId());
        }

        return save(ingestedDocument);
    }

    private static IngestedDocument ingestedDocument(MultipartFile multipartFile, String fileName, Resource newFileResource) {
        String contentType = multipartFile.getContentType();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(IngestedDocument.ORIGINAL_FILE_NAME, fileName);
        metadata.put(IngestedDocument.FILE_SIZE_BYTES, multipartFile.getSize());

        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setDocumentStatus(DocumentStatus.QUEUED);
        ingestedDocument.setFileName(fileName);
        ingestedDocument.setContentType(contentType);
        ingestedDocument.setDocumentSource(DocumentSource.USER);
        ingestedDocument.setMetadata(metadata);

        try (InputStream inputStream = newFileResource.getInputStream()) {
            byte[] fileContent = inputStream.readAllBytes();
            ingestedDocument.setFileData(fileContent);
        } catch (IOException e) {
            throw new ChatException("Failed to upload document", e);
        }

        return ingestedDocument;
    }
}
