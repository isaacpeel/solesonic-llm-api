package com.solesonic.service.ingestion;

import com.solesonic.exception.ChatException;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.VectorDocument;
import com.solesonic.service.rag.VectorStoreService;
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

import static com.solesonic.model.ingestion.IngestedDocument.REPLACED_BY_ID;
import static com.solesonic.model.ingestion.IngestedDocument.SOURCE_URI;
import static org.springframework.http.MediaType.TEXT_HTML_VALUE;

@Service
public class UriIngestionService {
    private static final Logger log = LoggerFactory.getLogger(UriIngestionService.class);

    private final IngestedDocumentService ingestedDocumentService;
    private final VectorStoreService vectorStoreService;

    public UriIngestionService(IngestedDocumentService ingestedDocumentService,
                               VectorStoreService vectorStoreService) {
        this.ingestedDocumentService = ingestedDocumentService;
        this.vectorStoreService = vectorStoreService;
    }

    public IngestedDocument queue(String uri) {
        log.info("Queueing URI: {}", uri);

        String validatedUri = validate(uri);

        List<IngestedDocument> existingIngestedDocuments = ingestedDocumentService.findBySourceUri(validatedUri);

        IngestedDocument ingestedDocument = ingestedDocument(validatedUri);
        IngestedDocument queuedIngestedDocument = ingestedDocumentService.save(ingestedDocument);

        replaceExisting(existingIngestedDocuments, queuedIngestedDocument);

        return queuedIngestedDocument;
    }

    private void replaceExisting(List<IngestedDocument> existingIngestedDocuments, IngestedDocument queuedIngestedDocument) {
        if (CollectionUtils.isEmpty(existingIngestedDocuments)) {
            return;
        }

        IngestedDocument currentIngestedDocument = existingIngestedDocuments.stream()
                .max(Comparator.comparing(IngestedDocument::getCreated))
                .orElse(null);

        assert currentIngestedDocument != null;

        List<VectorDocument> vectorDocuments = vectorStoreService.findByIngestedDocumentId(currentIngestedDocument.getId());
        vectorStoreService.delete(vectorDocuments);

        Map<String, Object> currentMetadata = currentIngestedDocument.getMetadata();
        currentMetadata.put(REPLACED_BY_ID, queuedIngestedDocument.getId());

        ingestedDocumentService.update(currentIngestedDocument, DocumentStatus.REPLACED);
    }

    private static IngestedDocument ingestedDocument(String uri) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(SOURCE_URI, uri);

        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setDocumentStatus(DocumentStatus.QUEUED);
        ingestedDocument.setFileName(uri);
        ingestedDocument.setContentType(TEXT_HTML_VALUE);
        ingestedDocument.setFileData(new byte[0]);
        ingestedDocument.setDocumentSource(DocumentSource.URI);
        ingestedDocument.setMetadata(metadata);
        ingestedDocument.setCreated(ZonedDateTime.now());
        ingestedDocument.setUpdated(ZonedDateTime.now());

        return ingestedDocument;
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
