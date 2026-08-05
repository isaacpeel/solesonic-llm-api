package com.solesonic.service.rag;

import com.solesonic.exception.ChatException;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.training.DocumentStatus;
import com.solesonic.model.training.TrainingDocument;
import com.solesonic.model.training.VectorDocument;
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

import static com.solesonic.model.training.TrainingDocument.REPLACED_BY_ID;
import static com.solesonic.model.training.TrainingDocument.SOURCE_URI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UriTrainingServiceTest {

    private static final String TEST_URI = "https://example.com/article";

    @Mock
    private TrainingDocumentService trainingDocumentService;

    @Mock
    private VectorStoreService vectorStoreService;

    private UriTrainingService uriTrainingService;

    @BeforeEach
    void beforeEach() {
        uriTrainingService = new UriTrainingService(trainingDocumentService, vectorStoreService);
    }

    @Test
    void test_queue_new_uri() {
        when(trainingDocumentService.findBySourceUri(TEST_URI)).thenReturn(List.of());
        when(trainingDocumentService.save(any(TrainingDocument.class))).thenAnswer(invocation -> {
            TrainingDocument trainingDocument = invocation.getArgument(0);
            trainingDocument.setId(UUID.randomUUID());
            return trainingDocument;
        });

        TrainingDocument queuedTrainingDocument = uriTrainingService.queue(TEST_URI);

        assertThat(queuedTrainingDocument.getDocumentSource()).isEqualTo(DocumentSource.URI);
        assertThat(queuedTrainingDocument.getDocumentStatus()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(queuedTrainingDocument.getMetadata().get(SOURCE_URI)).isEqualTo(TEST_URI);

        verify(vectorStoreService, never()).findByTrainingDocumentId(any());
        verify(trainingDocumentService, never()).update(any(), eq(DocumentStatus.REPLACED));
    }

    @Test
    void test_queue_resubmitted_uri_replaces_existing() {
        UUID existingTrainingDocumentId = UUID.randomUUID();

        TrainingDocument existingTrainingDocument = new TrainingDocument();
        existingTrainingDocument.setId(existingTrainingDocumentId);
        existingTrainingDocument.setCreated(ZonedDateTime.now().minusDays(1));

        Map<String, Object> existingMetadata = new HashMap<>();
        existingMetadata.put(SOURCE_URI, TEST_URI);
        existingTrainingDocument.setMetadata(existingMetadata);

        when(trainingDocumentService.findBySourceUri(TEST_URI)).thenReturn(List.of(existingTrainingDocument));
        when(trainingDocumentService.save(any(TrainingDocument.class))).thenAnswer(invocation -> {
            TrainingDocument trainingDocument = invocation.getArgument(0);
            trainingDocument.setId(UUID.randomUUID());
            return trainingDocument;
        });

        VectorDocument vectorDocument = new VectorDocument();
        when(vectorStoreService.findByTrainingDocumentId(existingTrainingDocumentId)).thenReturn(List.of(vectorDocument));

        TrainingDocument queuedTrainingDocument = uriTrainingService.queue(TEST_URI);

        verify(vectorStoreService, times(1)).findByTrainingDocumentId(existingTrainingDocumentId);
        verify(vectorStoreService, times(1)).delete(List.of(vectorDocument));
        verify(trainingDocumentService, times(1)).update(existingTrainingDocument, DocumentStatus.REPLACED);

        assertThat(existingTrainingDocument.getMetadata().get(REPLACED_BY_ID)).isEqualTo(queuedTrainingDocument.getId());
    }

    @Test
    void test_queue_rejects_non_http_scheme() {
        assertThatThrownBy(() -> uriTrainingService.queue("ftp://example.com/file"))
                .isInstanceOf(ChatException.class);

        verifyNoInteractions(trainingDocumentService, vectorStoreService);
    }

    @Test
    void test_queue_rejects_malformed_uri() {
        assertThatThrownBy(() -> uriTrainingService.queue("not a uri"))
                .isInstanceOf(ChatException.class);

        verifyNoInteractions(trainingDocumentService, vectorStoreService);
    }

    @Test
    void test_queue_rejects_blank_uri() {
        assertThatCode(() -> uriTrainingService.queue(""))
                .isInstanceOf(ChatException.class);

        verifyNoInteractions(trainingDocumentService, vectorStoreService);
    }
}
