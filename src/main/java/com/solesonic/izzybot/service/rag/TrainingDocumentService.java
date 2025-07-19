package com.solesonic.izzybot.service.rag;

import com.solesonic.izzybot.exception.ChatException;
import com.solesonic.izzybot.model.training.DocumentStatus;
import com.solesonic.izzybot.model.training.StatusHistory;
import com.solesonic.izzybot.model.training.TrainingDocument;
import com.solesonic.izzybot.repository.ollama.StatusHistoryRepository;
import com.solesonic.izzybot.repository.ollama.TrainingDocumentRepository;
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
import java.util.List;
import java.util.UUID;

import static com.solesonic.izzybot.model.training.DocumentStatus.FAILED;

@Service
public class TrainingDocumentService {
    private static final Logger log = LoggerFactory.getLogger(TrainingDocumentService.class);
    private final TrainingDocumentRepository trainingDocumentRepository;
    private final StatusHistoryRepository statusHistoryRepository;

    public TrainingDocumentService(TrainingDocumentRepository trainingDocumentRepository,
                                   StatusHistoryRepository statusHistoryRepository) {
        this.trainingDocumentRepository = trainingDocumentRepository;
        this.statusHistoryRepository = statusHistoryRepository;
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

    public TrainingDocument update(TrainingDocument trainingDocument, DocumentStatus documentStatus) {
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

        TrainingDocument trainingDocument = new TrainingDocument();
        trainingDocument.setDocumentStatus(DocumentStatus.QUEUED);
        trainingDocument.setFileName(fileName);
        trainingDocument.setContentType(contentType);

        try (InputStream inputStream = newFileResource.getInputStream()) {
            byte[] fileContent = inputStream.readAllBytes();
            trainingDocument.setFileData(fileContent);
        } catch (IOException e) {
            throw new ChatException("Failed to upload document", e);
        }

        return trainingDocument;
    }
}
