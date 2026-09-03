package com.solesonic.service.ingestion;

import com.solesonic.exception.ChatException;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.VectorDocument;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.service.rag.VectorStoreService;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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

    /**
     * Queues a URI for fetch and embedding at the scope its caller names.
     * <p>
     * The scope and owner are arguments with no default. Before they were, this path never called
     * {@code setScope} at all and every URI document was written with none recorded — invisible to a
     * scoped filter, and only ever reaching retrieval because embedding treats a null scope as
     * {@code GLOBAL}. There is now no call site on which the scope can be left unsaid.
     */
    public IngestedDocument queue(String uri, RetrievalScope scope, UUID ownerId) {
        log.info("Queueing URI: {} at {} scope", uri, scope);

        if (scope == RetrievalScope.CHAT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Documents cannot be ingested at CHAT scope; attach them to a message instead");
        }

        String validatedUri = validate(uri);

        List<IngestedDocument> existingIngestedDocuments =
                inSameCollection(ingestedDocumentService.findBySourceUri(validatedUri), scope, ownerId);

        IngestedDocument ingestedDocument = ingestedDocument(validatedUri);
        ingestedDocument.setScope(scope);
        ingestedDocument.setUserId(scope == RetrievalScope.USER ? ownerId : null);

        IngestedDocument queuedIngestedDocument = ingestedDocumentService.save(ingestedDocument);

        replaceExisting(existingIngestedDocuments, queuedIngestedDocument);

        return queuedIngestedDocument;
    }

    /**
     * Only a document in the same collection may be superseded by this one.
     * <p>
     * {@code findBySourceUri} matches on the URI alone, so without this one user re-ingesting a
     * public page would mark every other user's copy {@code REPLACED} and delete its chunks. The
     * same URI at two scopes is two documents, exactly as the same file name at two scopes is.
     */
    private static List<IngestedDocument> inSameCollection(List<IngestedDocument> existingIngestedDocuments,
                                                           RetrievalScope scope,
                                                           UUID ownerId) {
        return existingIngestedDocuments.stream()
                .filter(existing -> existing.getScope() == scope)
                .filter(existing -> scope != RetrievalScope.USER || Objects.equals(existing.getUserId(), ownerId))
                .toList();
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
