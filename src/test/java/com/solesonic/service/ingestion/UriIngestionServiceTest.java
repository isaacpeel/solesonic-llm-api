package com.solesonic.service.ingestion;

import com.solesonic.exception.ChatException;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.VectorDocument;
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

        IngestedDocument queuedIngestedDocument = uriIngestionService.queue(TEST_URI);

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

        IngestedDocument queuedIngestedDocument = uriIngestionService.queue(TEST_URI);

        verify(vectorStoreService, times(1)).findByIngestedDocumentId(existingIngestedDocumentId);
        verify(vectorStoreService, times(1)).delete(List.of(vectorDocument));
        verify(ingestedDocumentService, times(1)).update(existingIngestedDocument, DocumentStatus.REPLACED);

        assertThat(existingIngestedDocument.getMetadata().get(REPLACED_BY_ID)).isEqualTo(queuedIngestedDocument.getId());
    }

    @Test
    void test_queue_rejects_non_http_scheme() {
        assertThatThrownBy(() -> uriIngestionService.queue("ftp://example.com/file"))
                .isInstanceOf(ChatException.class);

        verifyNoInteractions(ingestedDocumentService, vectorStoreService);
    }

    @Test
    void test_queue_rejects_malformed_uri() {
        assertThatThrownBy(() -> uriIngestionService.queue("not a uri"))
                .isInstanceOf(ChatException.class);

        verifyNoInteractions(ingestedDocumentService, vectorStoreService);
    }

    @Test
    void test_queue_rejects_blank_uri() {
        assertThatCode(() -> uriIngestionService.queue(""))
                .isInstanceOf(ChatException.class);

        verifyNoInteractions(ingestedDocumentService, vectorStoreService);
    }
}
