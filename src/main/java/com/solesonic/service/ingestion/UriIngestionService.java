package com.solesonic.service.ingestion;

import com.solesonic.exception.ChatException;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.VectorDocument;
import com.solesonic.model.rag.DocumentPrincipal;
import com.solesonic.model.rag.GrantKind;
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
import java.util.Set;
import java.util.UUID;

import static com.solesonic.model.ingestion.IngestedDocument.REPLACED_BY_ID;
import static com.solesonic.model.ingestion.IngestedDocument.SOURCE_URI;
import static org.springframework.http.MediaType.TEXT_HTML_VALUE;

@Service
public class UriIngestionService {
    private static final Logger log = LoggerFactory.getLogger(UriIngestionService.class);

    private final IngestedDocumentService ingestedDocumentService;
    private final VectorStoreService vectorStoreService;
    private final DocumentEntitlementService documentEntitlementService;

    public UriIngestionService(IngestedDocumentService ingestedDocumentService,
                               VectorStoreService vectorStoreService,
                               DocumentEntitlementService documentEntitlementService) {
        this.ingestedDocumentService = ingestedDocumentService;
        this.vectorStoreService = vectorStoreService;
        this.documentEntitlementService = documentEntitlementService;
    }

    /**
     * Queues a URI for fetch and embedding at the scope its caller names.
     * <p>
     * The scope and owner are arguments with no default. Before they were, this path never called
     * {@code setScope} at all and every URI document was written with none recorded — invisible to a
     * scoped filter, and only ever reaching retrieval because embedding treats a null scope as
     * {@code GLOBAL}. There is now no call site on which the scope can be left unsaid.
     * Queues a URI into the shared corpus, managed by the {@code rag-admin} role.
     */
    public IngestedDocument queueGlobal(String uri, UUID uploaderId) {
        return queue(uri, DocumentPrincipal.global(), DocumentPrincipal.ragAdmin(), uploaderId);
    }

    /**
     * Queues a URI into one user's library, retrievable and managed by them.
     */
    public IngestedDocument queueForUser(String uri, UUID userId) {
        DocumentPrincipal owner = DocumentPrincipal.user(userId);

        return queue(uri, owner, owner, userId);
    }

    /**
     * Queues a URI for fetch and embedding, granted to the principal its collection names.
     * <p>
     * Both principals are arguments with no default. Before they were, this path recorded no
     * audience at all and every URI document was written with none — invisible to a scoped filter,
     * and only ever reaching retrieval because embedding treated the absence as {@code GLOBAL}.
     * There is now no call site on which the audience can be left unsaid.
     */
    private IngestedDocument queue(String uri,
                                   DocumentPrincipal retrievableBy,
                                   DocumentPrincipal managedBy,
                                   UUID uploaderId) {
        log.info("Queueing URI: {} for {}", uri, retrievableBy.key());

        String validatedUri = validate(uri);

        List<IngestedDocument> existingIngestedDocuments =
                inSameCollection(ingestedDocumentService.findBySourceUri(validatedUri), retrievableBy);

        IngestedDocument queuedIngestedDocument = ingestedDocumentService.saveWithOwnership(
                ingestedDocument(validatedUri), retrievableBy, managedBy, uploaderId, null);

        replaceExisting(existingIngestedDocuments, queuedIngestedDocument);

        return queuedIngestedDocument;
    }

    /**
     * Only a document with the same audience may be superseded by this one.
     * <p>
     * {@code findBySourceUri} matches on the URI alone, so without this one user re-ingesting a
     * public page would mark every other user's copy {@code REPLACED} and delete its chunks. The
     * same URI granted to two different principals is two documents, exactly as the same file name
     * in two libraries is.
     * <p>
     * Comparing the retrieve grant <em>set</em> rather than a scope and an owner is strictly more
     * correct than what it replaces: {@code scope == scope && userId == ownerId} could not express a
     * document shared with more than one principal, and would have treated a shared copy and a
     * private one as the same collection.
     */
    private List<IngestedDocument> inSameCollection(List<IngestedDocument> existingIngestedDocuments,
                                                    DocumentPrincipal retrievableBy) {
        Set<DocumentPrincipal> intended = Set.of(retrievableBy);

        return existingIngestedDocuments.stream()
                .filter(existing -> Set.copyOf(
                                documentEntitlementService.principals(existing.getId(), GrantKind.RETRIEVE))
                        .equals(intended))
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
