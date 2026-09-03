package com.solesonic.service.ingestion;

import com.solesonic.exception.ChatException;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.VectorDocument;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.service.rag.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UriIngestionServiceTest {

    private static final String TEST_URI = "https://example.com/article";

    @Mock
    private IngestedDocumentService ingestedDocumentService;

    @Mock
    private VectorStoreService vectorStoreService;

    private UriIngestionService uriIngestionService;

    @BeforeEach
    void beforeEach() {
        uriIngestionService = new UriIngestionService(ingestedDocumentService, vectorStoreService);
    }

    @Test
    void test_queue_new_uri() {
        when(ingestedDocumentService.findBySourceUri(TEST_URI)).thenReturn(List.of());
        when(ingestedDocumentService.save(any(IngestedDocument.class))).thenAnswer(invocation -> {
            IngestedDocument ingestedDocument = invocation.getArgument(0);
            ingestedDocument.setId(UUID.randomUUID());
            return ingestedDocument;
        });

        IngestedDocument queuedIngestedDocument = uriIngestionService.queue(TEST_URI, RetrievalScope.GLOBAL, null);

        assertThat(queuedIngestedDocument.getDocumentSource()).isEqualTo(DocumentSource.URI);
        assertThat(queuedIngestedDocument.getDocumentStatus()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(queuedIngestedDocument.getMetadata().get(SOURCE_URI)).isEqualTo(TEST_URI);

        verify(vectorStoreService, never()).findByIngestedDocumentId(any());
        verify(ingestedDocumentService, never()).update(any(), eq(DocumentStatus.REPLACED));
    }

    @Test
    void test_queue_resubmitted_uri_replaces_existing() {
        UUID existingIngestedDocumentId = UUID.randomUUID();

        IngestedDocument existingIngestedDocument = new IngestedDocument();
        existingIngestedDocument.setId(existingIngestedDocumentId);
        existingIngestedDocument.setScope(RetrievalScope.GLOBAL);
        existingIngestedDocument.setCreated(ZonedDateTime.now().minusDays(1));

        Map<String, Object> existingMetadata = new HashMap<>();
        existingMetadata.put(SOURCE_URI, TEST_URI);
        existingIngestedDocument.setMetadata(existingMetadata);

        when(ingestedDocumentService.findBySourceUri(TEST_URI)).thenReturn(List.of(existingIngestedDocument));
        when(ingestedDocumentService.save(any(IngestedDocument.class))).thenAnswer(invocation -> {
            IngestedDocument ingestedDocument = invocation.getArgument(0);
            ingestedDocument.setId(UUID.randomUUID());
            return ingestedDocument;
        });

        VectorDocument vectorDocument = new VectorDocument();
        when(vectorStoreService.findByIngestedDocumentId(existingIngestedDocumentId)).thenReturn(List.of(vectorDocument));

        IngestedDocument queuedIngestedDocument = uriIngestionService.queue(TEST_URI, RetrievalScope.GLOBAL, null);

        verify(vectorStoreService, times(1)).findByIngestedDocumentId(existingIngestedDocumentId);
        verify(vectorStoreService, times(1)).delete(List.of(vectorDocument));
        verify(ingestedDocumentService, times(1)).update(existingIngestedDocument, DocumentStatus.REPLACED);

        assertThat(existingIngestedDocument.getMetadata().get(REPLACED_BY_ID)).isEqualTo(queuedIngestedDocument.getId());
    }

    @Test
    void test_queue_rejects_non_http_scheme() {
        assertThatThrownBy(() -> uriIngestionService.queue("ftp://example.com/file", RetrievalScope.GLOBAL, null))
                .isInstanceOf(ChatException.class);

        verifyNoInteractions(ingestedDocumentService, vectorStoreService);
    }

    @Test
    void test_queue_rejects_malformed_uri() {
        assertThatThrownBy(() -> uriIngestionService.queue("not a uri", RetrievalScope.GLOBAL, null))
                .isInstanceOf(ChatException.class);

        verifyNoInteractions(ingestedDocumentService, vectorStoreService);
    }

    @Test
    void test_queue_rejects_blank_uri() {
        assertThatCode(() -> uriIngestionService.queue("", RetrievalScope.GLOBAL, null))
                .isInstanceOf(ChatException.class);

        verifyNoInteractions(ingestedDocumentService, vectorStoreService);
    }

    /**
     * The gap this signature closes. Before the scope was an argument this path never called
     * {@code setScope}, so every URI document was written with none recorded and reached retrieval
     * only because embedding treats a null scope as {@code GLOBAL}.
     */
    @Test
    void test_queue_records_the_requested_scope_and_owner() {
        UUID ownerId = UUID.randomUUID();

        when(ingestedDocumentService.findBySourceUri(TEST_URI)).thenReturn(List.of());
        when(ingestedDocumentService.save(any(IngestedDocument.class))).thenAnswer(invocation -> {
            IngestedDocument ingestedDocument = invocation.getArgument(0);
            ingestedDocument.setId(UUID.randomUUID());
            return ingestedDocument;
        });

        IngestedDocument queuedIngestedDocument =
                uriIngestionService.queue(TEST_URI, RetrievalScope.USER, ownerId);

        assertThat(queuedIngestedDocument.getScope()).isEqualTo(RetrievalScope.USER);
        assertThat(queuedIngestedDocument.getUserId()).isEqualTo(ownerId);
    }

    @Test
    void test_queue_at_global_scope_records_no_owner() {
        when(ingestedDocumentService.findBySourceUri(TEST_URI)).thenReturn(List.of());
        when(ingestedDocumentService.save(any(IngestedDocument.class))).thenAnswer(invocation -> {
            IngestedDocument ingestedDocument = invocation.getArgument(0);
            ingestedDocument.setId(UUID.randomUUID());
            return ingestedDocument;
        });

        IngestedDocument queuedIngestedDocument =
                uriIngestionService.queue(TEST_URI, RetrievalScope.GLOBAL, UUID.randomUUID());

        assertThat(queuedIngestedDocument.getScope()).isEqualTo(RetrievalScope.GLOBAL);
        assertThat(queuedIngestedDocument.getUserId()).isNull();
    }

    /**
     * {@code findBySourceUri} matches on the URI alone. Re-ingesting a public page privately must
     * not mark the shared copy REPLACED and delete its chunks out from under every other user.
     */
    @Test
    void test_queue_does_not_replace_a_document_in_another_collection() {
        IngestedDocument sharedIngestedDocument = new IngestedDocument();
        sharedIngestedDocument.setId(UUID.randomUUID());
        sharedIngestedDocument.setScope(RetrievalScope.GLOBAL);
        sharedIngestedDocument.setCreated(ZonedDateTime.now().minusDays(1));

        when(ingestedDocumentService.findBySourceUri(TEST_URI)).thenReturn(List.of(sharedIngestedDocument));
        when(ingestedDocumentService.save(any(IngestedDocument.class))).thenAnswer(invocation -> {
            IngestedDocument ingestedDocument = invocation.getArgument(0);
            ingestedDocument.setId(UUID.randomUUID());
            return ingestedDocument;
        });

        uriIngestionService.queue(TEST_URI, RetrievalScope.USER, UUID.randomUUID());

        verify(vectorStoreService, never()).findByIngestedDocumentId(any());
        verify(ingestedDocumentService, never()).update(any(), eq(DocumentStatus.REPLACED));
    }

    /**
     * Two users ingesting the same URI privately keep two documents; neither supersedes the other.
     */
    @Test
    void test_queue_does_not_replace_another_users_copy_of_the_same_uri() {
        IngestedDocument otherUsersIngestedDocument = new IngestedDocument();
        otherUsersIngestedDocument.setId(UUID.randomUUID());
        otherUsersIngestedDocument.setScope(RetrievalScope.USER);
        otherUsersIngestedDocument.setUserId(UUID.randomUUID());
        otherUsersIngestedDocument.setCreated(ZonedDateTime.now().minusDays(1));

        when(ingestedDocumentService.findBySourceUri(TEST_URI)).thenReturn(List.of(otherUsersIngestedDocument));
        when(ingestedDocumentService.save(any(IngestedDocument.class))).thenAnswer(invocation -> {
            IngestedDocument ingestedDocument = invocation.getArgument(0);
            ingestedDocument.setId(UUID.randomUUID());
            return ingestedDocument;
        });

        uriIngestionService.queue(TEST_URI, RetrievalScope.USER, UUID.randomUUID());

        verify(ingestedDocumentService, never()).update(any(), eq(DocumentStatus.REPLACED));
    }

    @Test
    void test_queue_rejects_chat_scope() {
        assertThatThrownBy(() -> uriIngestionService.queue(TEST_URI, RetrievalScope.CHAT, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(ingestedDocumentService, vectorStoreService);
    }
}
