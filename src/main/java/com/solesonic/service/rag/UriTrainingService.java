package com.solesonic.service.rag;

import com.solesonic.exception.ChatException;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.training.DocumentStatus;
import com.solesonic.model.training.TrainingDocument;
import com.solesonic.model.training.VectorDocument;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.solesonic.model.training.TrainingDocument.REPLACED_BY_ID;
import static com.solesonic.model.training.TrainingDocument.SOURCE_URI;
import static org.springframework.http.MediaType.TEXT_HTML_VALUE;

@Service
public class UriTrainingService {
    private static final Logger log = LoggerFactory.getLogger(UriTrainingService.class);

    private final TrainingDocumentService trainingDocumentService;
    private final VectorStoreService vectorStoreService;

    public UriTrainingService(TrainingDocumentService trainingDocumentService,
                              VectorStoreService vectorStoreService) {
        this.trainingDocumentService = trainingDocumentService;
        this.vectorStoreService = vectorStoreService;
    }

    public TrainingDocument queue(String uri) {
        String validatedUri = validate(uri);

        List<TrainingDocument> existingTrainingDocuments = trainingDocumentService.findBySourceUri(validatedUri);

        TrainingDocument trainingDocument = trainingDocument(validatedUri);
        TrainingDocument queuedTrainingDocument = trainingDocumentService.save(trainingDocument);

        replaceExisting(existingTrainingDocuments, queuedTrainingDocument);

        return queuedTrainingDocument;
    }

    private void replaceExisting(List<TrainingDocument> existingTrainingDocuments, TrainingDocument queuedTrainingDocument) {
        if (CollectionUtils.isEmpty(existingTrainingDocuments)) {
            return;
        }

        TrainingDocument currentTrainingDocument = existingTrainingDocuments.stream()
                .max(Comparator.comparing(TrainingDocument::getCreated))
                .orElse(null);

        assert currentTrainingDocument != null;

        List<VectorDocument> vectorDocuments = vectorStoreService.findByTrainingDocumentId(currentTrainingDocument.getId());
        vectorStoreService.delete(vectorDocuments);

        Map<String, Object> currentMetadata = currentTrainingDocument.getMetadata();
        currentMetadata.put(REPLACED_BY_ID, queuedTrainingDocument.getId());

        trainingDocumentService.update(currentTrainingDocument, DocumentStatus.REPLACED);
    }

    private static TrainingDocument trainingDocument(String uri) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(SOURCE_URI, uri);

        TrainingDocument trainingDocument = new TrainingDocument();
        trainingDocument.setDocumentStatus(DocumentStatus.QUEUED);
        trainingDocument.setFileName(uri);
        trainingDocument.setContentType(TEXT_HTML_VALUE);
        trainingDocument.setFileData(new byte[0]);
        trainingDocument.setDocumentSource(DocumentSource.URI);
        trainingDocument.setMetadata(metadata);
        trainingDocument.setCreated(ZonedDateTime.now());
        trainingDocument.setUpdated(ZonedDateTime.now());

        return trainingDocument;
    }

    private static String validate(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new ChatException("Uri must not be blank");
        }

        URI parsedUri;

        try {
            parsedUri = new URI(uri);
        } catch (URISyntaxException e) {
            throw new ChatException("Malformed uri: " + uri, e);
        }

        String scheme = parsedUri.getScheme();

        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ChatException("Uri scheme must be http or https: " + uri);
        }

        return parsedUri.toString();
    }
}
