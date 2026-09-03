package com.solesonic.service.ingestion;

import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.StatusHistory;
import com.solesonic.repository.ingestion.StatusHistoryRepository;
import com.solesonic.service.etl.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scheduler's single-flight gate, and what it is allowed to be blocked by.
 * <p>
 * The inline-ingest exclusion itself lives in the JPQL of
 * {@link StatusHistoryRepository#findInProgress()}, and {@link StatusHistoryRepository#findQueued()}
 * now carries no ownership filter at all. Nothing here executes either — these mock the repository,
 * so what they pin is that {@code processQueued} drains its backlog whenever the in-progress set it
 * is handed is empty, and stands down whenever it is not. Proving the queries themselves needs a
 * repository-level test against a real database, which this project has no harness for: there is no
 * {@code @DataJpaTest} anywhere, and {@code application-test.properties} resolves
 * {@code SPRING_DATASOURCE_URL} from an environment variable the run configurations do not set.
 * <p>
 * The documents here are distinguished by {@link DocumentSource} rather than by scope, because that
 * is what the exclusion is keyed on: {@code CHAT} means "ingested inline on a chat turn", which is
 * the only thing the scheduler must not wait for.
 */
@ExtendWith(MockitoExtension.class)
class StatusHistoryServiceTest {

    private static final UUID UPLOADED_DOCUMENT_ID = UUID.randomUUID();
    private static final UUID URI_DOCUMENT_ID = UUID.randomUUID();
    private static final UUID CHAT_UPLOAD_DOCUMENT_ID = UUID.randomUUID();

    @Mock
    private StatusHistoryRepository statusHistoryRepository;

    @Mock
    private DocumentService documentService;

    @Mock
    private IngestedDocumentService ingestedDocumentService;

    private StatusHistoryService statusHistoryService;

    @BeforeEach
    void beforeEach() {
        statusHistoryService = new StatusHistoryService(statusHistoryRepository,
                documentService,
                ingestedDocumentService);
    }

    /**
     * A chat attachment being indexed writes an {@code IN_PROGRESS} row like anything else, but the
     * scheduler never sees it: {@code findInProgress()} excludes {@link DocumentSource#CHAT}, so the
     * backlog drains on the same tick rather than waiting out the chat turn.
     */
    @Test
    void inlineChatIngestionDoesNotHoldOffTheBacklog() {
        IngestedDocument uploadedDocument = ingestedDocument(UPLOADED_DOCUMENT_ID, DocumentSource.USER);
        IngestedDocument uriDocument = ingestedDocument(URI_DOCUMENT_ID, DocumentSource.URI);

        when(statusHistoryRepository.findInProgress()).thenReturn(List.of());
        when(statusHistoryRepository.findQueued()).thenReturn(List.of(
                statusHistory(UPLOADED_DOCUMENT_ID, DocumentStatus.QUEUED),
                statusHistory(URI_DOCUMENT_ID, DocumentStatus.QUEUED)));
        when(ingestedDocumentService.get(UPLOADED_DOCUMENT_ID)).thenReturn(uploadedDocument);
        when(ingestedDocumentService.get(URI_DOCUMENT_ID)).thenReturn(uriDocument);

        statusHistoryService.processQueued();

        verify(ingestedDocumentService).update(uploadedDocument, DocumentStatus.IN_PROGRESS);
        verify(ingestedDocumentService).update(uriDocument, DocumentStatus.IN_PROGRESS);
        verify(documentService).resourceToVectorStore(UPLOADED_DOCUMENT_ID);
        verify(documentService).resourceToVectorStore(URI_DOCUMENT_ID);
        verify(ingestedDocumentService, never()).update(any(IngestedDocument.class), eq(DocumentStatus.FAILED));
    }

    /**
     * A document uploaded straight to a conversation through {@code POST /chats/{chatId}/documents}
     * is chat-owned but asynchronous, and the scheduler is what ingests it.
     * <p>
     * This is the service half of the regression that left such a document at {@code QUEUED}
     * forever. The other half — {@code findQueued()} no longer filtering it out — is JPQL this
     * cannot execute. What is pinned here is that nothing in {@code processQueued} re-introduces the
     * exclusion above the query: whatever the backlog hands back is ingested, whoever owns it.
     */
    @Test
    void drainsADocumentUploadedStraightToAConversation() {
        IngestedDocument chatUpload = ingestedDocument(CHAT_UPLOAD_DOCUMENT_ID, DocumentSource.USER);

        when(statusHistoryRepository.findInProgress()).thenReturn(List.of());
        when(statusHistoryRepository.findQueued())
                .thenReturn(List.of(statusHistory(CHAT_UPLOAD_DOCUMENT_ID, DocumentStatus.QUEUED)));
        when(ingestedDocumentService.get(CHAT_UPLOAD_DOCUMENT_ID)).thenReturn(chatUpload);

        statusHistoryService.processQueued();

        verify(ingestedDocumentService).update(chatUpload, DocumentStatus.IN_PROGRESS);
        verify(documentService).resourceToVectorStore(CHAT_UPLOAD_DOCUMENT_ID);
        verify(ingestedDocumentService, never()).update(any(IngestedDocument.class), eq(DocumentStatus.FAILED));
    }

    /**
     * The single-flight gate still holds for the documents it does watch: one mid-ingest keeps the
     * next tick from starting another.
     */
    @Test
    void inProgressDocumentStillHoldsOffTheBacklog() {
        when(statusHistoryRepository.findInProgress())
                .thenReturn(List.of(statusHistory(UPLOADED_DOCUMENT_ID, DocumentStatus.KEYWORD_ENRICHING)));

        statusHistoryService.processQueued();

        verify(statusHistoryRepository, never()).findQueued();
        verify(documentService, never()).resourceToVectorStore(any(UUID.class));
    }

    private IngestedDocument ingestedDocument(UUID documentId, DocumentSource documentSource) {
        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(documentId);
        ingestedDocument.setDocumentSource(documentSource);
        return ingestedDocument;
    }

    private StatusHistory statusHistory(UUID documentId, DocumentStatus documentStatus) {
        StatusHistory statusHistory = new StatusHistory();
        statusHistory.setDocumentId(documentId);
        statusHistory.setDocumentStatus(documentStatus);
        statusHistory.setTimestamp(ZonedDateTime.now());
        return statusHistory;
    }
}
