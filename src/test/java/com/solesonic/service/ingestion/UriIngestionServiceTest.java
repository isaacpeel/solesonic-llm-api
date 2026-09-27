package com.solesonic.service.ingestion;

import com.solesonic.exception.ChatException;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.VectorDocument;
import com.solesonic.model.rag.DocumentPrincipal;
import com.solesonic.model.rag.GrantKind;
import com.solesonic.service.rag.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.solesonic.model.ingestion.IngestedDocument.REPLACED_BY_ID;
import static com.solesonic.model.ingestion.IngestedDocument.SOURCE_URI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UriIngestionServiceTest {

    private static final String TEST_URI = "https://example.com/article";

    @Mock
    private IngestedDocumentService ingestedDocumentService;

    @Mock
    private VectorStoreService vectorStoreService;

    @Mock
    private DocumentEntitlementService documentEntitlementService;

    private UriIngestionService uriIngestionService;

    @BeforeEach
    void beforeEach() {
        uriIngestionService = new UriIngestionService(ingestedDocumentService,
                vectorStoreService,
                documentEntitlementService);
    }

    /**
     * The queued row is saved through {@code saveWithOwnership}, which is what writes its grants — a
     * URI document saved without them would be retrievable and manageable by nobody.
     */
    private void savesWithOwnership() {
        when(ingestedDocumentService.saveWithOwnership(any(IngestedDocument.class),
                any(DocumentPrincipal.class), any(DocumentPrincipal.class), any(), any()))
                .thenAnswer(invocation -> {
                    IngestedDocument ingestedDocument = invocation.getArgument(0);
                    ingestedDocument.setId(UUID.randomUUID());
                    return ingestedDocument;
                });
    }

    private static IngestedDocument existingUriDocument(UUID id) {
        IngestedDocument existingIngestedDocument = new IngestedDocument();
        existingIngestedDocument.setId(id);
        existingIngestedDocument.setCreated(ZonedDateTime.now().minusDays(1));

        Map<String, Object> existingMetadata = new HashMap<>();
        existingMetadata.put(SOURCE_URI, TEST_URI);
        existingIngestedDocument.setMetadata(existingMetadata);

        return existingIngestedDocument;
    }

    @Test
    void queuesANewUriIntoTheSharedCorpus() {
        when(ingestedDocumentService.findBySourceUri(TEST_URI)).thenReturn(List.of());
        savesWithOwnership();

        IngestedDocument queuedIngestedDocument = uriIngestionService.queueGlobal(TEST_URI, null);

        assertThat(queuedIngestedDocument.getDocumentSource()).isEqualTo(DocumentSource.URI);
        assertThat(queuedIngestedDocument.getDocumentStatus()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(queuedIngestedDocument.getMetadata().get(SOURCE_URI)).isEqualTo(TEST_URI);

        // Retrievable by everyone, managed by the role -- the uploading admin keeps no personal
        // grant, which is what makes granted_by the record of who added it. The null content is a
        // URI document having no bytes until DocumentService fetches them.
        verify(ingestedDocumentService).saveWithOwnership(any(IngestedDocument.class),
                eq(DocumentPrincipal.global()),
                eq(DocumentPrincipal.ragAdmin()),
                isNull(),
                isNull());

        verify(vectorStoreService, never()).findByIngestedDocumentId(any());
        verify(ingestedDocumentService, never()).update(any(), eq(DocumentStatus.REPLACED));
    }

    @Test
    void queuesAUriIntoOneUsersLibrary() {
        UUID userId = UUID.randomUUID();

        when(ingestedDocumentService.findBySourceUri(TEST_URI)).thenReturn(List.of());
        savesWithOwnership();

        uriIngestionService.queueForUser(TEST_URI, userId);

        verify(ingestedDocumentService).saveWithOwnership(any(IngestedDocument.class),
                eq(DocumentPrincipal.user(userId)),
                eq(DocumentPrincipal.user(userId)),
                eq(userId),
                isNull());
    }

    /**
     * Re-ingesting the same URI for the same audience supersedes the previous document: its chunks
     * go, and it is marked {@code REPLACED} with a pointer to what replaced it.
     */
    @Test
    void resubmittingAUriReplacesTheDocumentWithTheSameAudience() {
        UUID existingIngestedDocumentId = UUID.randomUUID();
        IngestedDocument existingIngestedDocument = existingUriDocument(existingIngestedDocumentId);

        when(ingestedDocumentService.findBySourceUri(TEST_URI))
                .thenReturn(List.of(existingIngestedDocument));
        when(documentEntitlementService.principals(existingIngestedDocumentId, GrantKind.RETRIEVE))
                .thenReturn(List.of(DocumentPrincipal.global()));
        savesWithOwnership();

        VectorDocument vectorDocument = new VectorDocument();
        when(vectorStoreService.findByIngestedDocumentId(existingIngestedDocumentId))
                .thenReturn(List.of(vectorDocument));

        IngestedDocument queuedIngestedDocument = uriIngestionService.queueGlobal(TEST_URI, null);

        verify(vectorStoreService, times(1)).findByIngestedDocumentId(existingIngestedDocumentId);
        verify(vectorStoreService, times(1)).delete(List.of(vectorDocument));
        verify(ingestedDocumentService, times(1)).update(existingIngestedDocument, DocumentStatus.REPLACED);

        assertThat(existingIngestedDocument.getMetadata().get(REPLACED_BY_ID))
                .isEqualTo(queuedIngestedDocument.getId());
    }

    /**
     * The rule that keeps one person's re-ingest from destroying another's copy.
     * <p>
     * {@code findBySourceUri} matches on the URI alone, so the same public page ingested by two
     * people comes back twice. Only the one with an identical retrieve grant set may be superseded —
     * a user's private copy is not the shared corpus's copy, and marking it {@code REPLACED} would
     * delete chunks belonging to someone who did nothing.
     */
    @Test
    void doesNotReplaceTheSameUriHeldByADifferentAudience() {
        UUID otherUsersDocumentId = UUID.randomUUID();
        IngestedDocument otherUsersDocument = existingUriDocument(otherUsersDocumentId);

        when(ingestedDocumentService.findBySourceUri(TEST_URI)).thenReturn(List.of(otherUsersDocument));
        when(documentEntitlementService.principals(otherUsersDocumentId, GrantKind.RETRIEVE))
                .thenReturn(List.of(DocumentPrincipal.user(UUID.randomUUID())));
        savesWithOwnership();

        uriIngestionService.queueGlobal(TEST_URI, null);

        verify(vectorStoreService, never()).findByIngestedDocumentId(any());
        verify(ingestedDocumentService, never()).update(any(), eq(DocumentStatus.REPLACED));
    }

    /**
     * A document shared with more than one principal is not the same collection as one granted to
     * only the first of them. The old {@code scope == scope && userId == ownerId} comparison could
     * not express this at all — it would have superseded the shared copy.
     */
    @Test
    void doesNotReplaceADocumentSharedMoreWidelyThanTheNewOne() {
        UUID userId = UUID.randomUUID();
        UUID sharedDocumentId = UUID.randomUUID();
        IngestedDocument sharedDocument = existingUriDocument(sharedDocumentId);

        when(ingestedDocumentService.findBySourceUri(TEST_URI)).thenReturn(List.of(sharedDocument));
        when(documentEntitlementService.principals(sharedDocumentId, GrantKind.RETRIEVE))
                .thenReturn(List.of(DocumentPrincipal.user(userId), DocumentPrincipal.global()));
        savesWithOwnership();

        uriIngestionService.queueForUser(TEST_URI, userId);

        verify(ingestedDocumentService, never()).update(any(), eq(DocumentStatus.REPLACED));
    }

    @Test
    void rejectsANonHttpScheme() {
        assertThatThrownBy(() -> uriIngestionService.queueGlobal("ftp://example.com/file", null))
                .isInstanceOf(ChatException.class);

        verifyNoInteractions(ingestedDocumentService, vectorStoreService, documentEntitlementService);
    }

    @Test
    void rejectsAMalformedUri() {
        assertThatThrownBy(() -> uriIngestionService.queueGlobal("not a uri", null))
                .isInstanceOf(ChatException.class);

        verifyNoInteractions(ingestedDocumentService, vectorStoreService, documentEntitlementService);
    }

    @Test
    void rejectsABlankUri() {
        assertThatCode(() -> uriIngestionService.queueGlobal("", null))
                .isInstanceOf(ChatException.class);

        verifyNoInteractions(ingestedDocumentService, vectorStoreService, documentEntitlementService);
    }
}
