package com.solesonic.service.ingestion;

import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.StatusHistory;
import com.solesonic.model.rag.RetrievalScope;
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
 * The {@code CHAT} exclusion itself lives in the JPQL of
 * {@link StatusHistoryRepository#findInProgress()} and {@link StatusHistoryRepository#findQueued()},
 * which nothing here executes — these mock the repository, so what they pin is that
 * {@code processQueued} drains its backlog whenever the in-progress set it is handed is empty, and
 * stands down whenever it is not. Proving the queries themselves exclude {@code CHAT} needs a
 * repository-level test against a real database, which this project has no harness for.
 */
@ExtendWith(MockitoExtension.class)
class StatusHistoryServiceTest {

    private static final UUID GLOBAL_DOCUMENT_ID = UUID.randomUUID();
    private static final UUID USER_DOCUMENT_ID = UUID.randomUUID();

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
     * A chat attachment being indexed writes an {@code IN_PROGRESS} row like any other scope, but the
     * scheduler never sees it: {@code findInProgress()} is scoped to {@code GLOBAL}/{@code USER}, so
     * the backlog drains on the same tick rather than waiting out the chat turn.
     */
    @Test
    void chatIngestionDoesNotHoldOffTheBacklog() {
        IngestedDocument globalDocument = ingestedDocument(GLOBAL_DOCUMENT_ID, RetrievalScope.GLOBAL);
        IngestedDocument userDocument = ingestedDocument(USER_DOCUMENT_ID, RetrievalScope.USER);

        when(statusHistoryRepository.findInProgress()).thenReturn(List.of());
        when(statusHistoryRepository.findQueued()).thenReturn(List.of(
                statusHistory(GLOBAL_DOCUMENT_ID, DocumentStatus.QUEUED),
                statusHistory(USER_DOCUMENT_ID, DocumentStatus.QUEUED)));
        when(ingestedDocumentService.get(GLOBAL_DOCUMENT_ID)).thenReturn(globalDocument);
        when(ingestedDocumentService.get(USER_DOCUMENT_ID)).thenReturn(userDocument);

        statusHistoryService.processQueued();

        verify(ingestedDocumentService).update(globalDocument, DocumentStatus.IN_PROGRESS);
        verify(ingestedDocumentService).update(userDocument, DocumentStatus.IN_PROGRESS);
        verify(documentService).resourceToVectorStore(GLOBAL_DOCUMENT_ID);
        verify(documentService).resourceToVectorStore(USER_DOCUMENT_ID);
        verify(ingestedDocumentService, never()).update(any(IngestedDocument.class), eq(DocumentStatus.FAILED));
    }

    /**
     * The single-flight gate still holds for the scopes it does watch: a {@code GLOBAL}/{@code USER}
     * document mid-ingest keeps the next tick from starting another one.
     */
    @Test
    void inProgressDocumentStillHoldsOffTheBacklog() {
        when(statusHistoryRepository.findInProgress())
                .thenReturn(List.of(statusHistory(GLOBAL_DOCUMENT_ID, DocumentStatus.KEYWORD_ENRICHING)));

        statusHistoryService.processQueued();

        verify(statusHistoryRepository, never()).findQueued();
        verify(documentService, never()).resourceToVectorStore(any(UUID.class));
    }

    private IngestedDocument ingestedDocument(UUID documentId, RetrievalScope scope) {
        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(documentId);
        ingestedDocument.setScope(scope);
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
