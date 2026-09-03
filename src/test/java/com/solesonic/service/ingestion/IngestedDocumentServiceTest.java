package com.solesonic.service.ingestion;

import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.DocumentStatusEntry;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.IngestedDocumentSummary;
import com.solesonic.model.ingestion.StatusHistory;
import com.solesonic.model.ingestion.VectorDocument;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.repository.ingestion.IngestedDocumentRepository;
import com.solesonic.repository.ingestion.StatusHistoryRepository;
import com.solesonic.service.rag.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.model.ingestion.IngestedDocument.FILE_SIZE_BYTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scoped read/write pairs, whose whole reason for existing is that neither one can reach the
 * other scope's documents.
 * <p>
 * Each pair is tested from both sides: that the right document comes back, and that a document in
 * the other collection is a {@code 404} rather than a leak. The ownership itself lives in the
 * repository's {@code where} clause, so what these assert is that the service asks the scoped query
 * and does nothing on an empty answer.
 */
@ExtendWith(MockitoExtension.class)
class IngestedDocumentServiceTest {

    private static final String FILE_NAME = "handbook.pdf";

    @Mock
    private IngestedDocumentRepository ingestedDocumentRepository;

    @Mock
    private StatusHistoryRepository statusHistoryRepository;

    @Mock
    private VectorStoreService vectorStoreService;

    private IngestedDocumentService ingestedDocumentService;

    private UUID documentId;
    private UUID userId;

    @BeforeEach
    void beforeEach() {
        documentId = UUID.randomUUID();
        userId = UUID.randomUUID();

        ingestedDocumentService = new IngestedDocumentService(ingestedDocumentRepository,
                statusHistoryRepository,
                vectorStoreService);
    }

    private IngestedDocument document(RetrievalScope scope, UUID ownerId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(FILE_SIZE_BYTES, 2048);

        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(documentId);
        ingestedDocument.setFileName(FILE_NAME);
        ingestedDocument.setContentType("application/pdf");
        ingestedDocument.setDocumentSource(DocumentSource.USER);
        ingestedDocument.setScope(scope);
        ingestedDocument.setUserId(ownerId);
        ingestedDocument.setMetadata(metadata);
        ingestedDocument.setCreated(ZonedDateTime.now());
        ingestedDocument.setUpdated(ZonedDateTime.now());

        return ingestedDocument;
    }

    @Test
    void listGlobalRendersSummariesWithTheirLatestStatus() {
        IngestedDocument ingestedDocument = document(RetrievalScope.GLOBAL, null);
        Pageable pageable = PageRequest.of(0, 20);

        when(ingestedDocumentRepository.findAllGlobal(pageable))
                .thenReturn(new PageImpl<>(List.of(ingestedDocument), pageable, 1));
        when(statusHistoryRepository.findLatestStatuses(List.of(documentId)))
                .thenReturn(List.of(new DocumentStatusEntry(documentId, DocumentStatus.COMPLETED)));

        List<IngestedDocumentSummary> summaries =
                ingestedDocumentService.listGlobal(pageable).getContent();

        assertThat(summaries).singleElement().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo(documentId);
            assertThat(summary.fileName()).isEqualTo(FILE_NAME);
            assertThat(summary.scope()).isEqualTo(RetrievalScope.GLOBAL);
            assertThat(summary.fileSizeBytes()).isEqualTo(2048L);
            assertThat(summary.documentStatus()).isEqualTo(DocumentStatus.COMPLETED);
        });

        verify(ingestedDocumentRepository, never()).findAll(any(Pageable.class));
    }

    /**
     * One status query for the page, not one per row. The batch call is what listing costs now, and
     * regressing to the per-row lookup would not otherwise fail anything.
     */
    @Test
    void listGlobalAsksForEveryStatusAtOnce() {
        Pageable pageable = PageRequest.of(0, 20);

        when(ingestedDocumentRepository.findAllGlobal(pageable))
                .thenReturn(new PageImpl<>(List.of(document(RetrievalScope.GLOBAL, null)), pageable, 1));
        when(statusHistoryRepository.findLatestStatuses(anyCollection())).thenReturn(List.of());

        ingestedDocumentService.listGlobal(pageable);

        verify(statusHistoryRepository).findLatestStatuses(List.of(documentId));
        verify(statusHistoryRepository, never()).findByDocumentId(any());
    }

    /**
     * An empty page must not ask for the statuses of nothing — {@code in ()} is not valid SQL on
     * every dialect, and the query would be pointless anyway.
     */
    @Test
    void listGlobalSkipsTheStatusQueryForAnEmptyPage() {
        Pageable pageable = PageRequest.of(3, 20);

        when(ingestedDocumentRepository.findAllGlobal(pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        assertThat(ingestedDocumentService.listGlobal(pageable).getContent()).isEmpty();

        verify(statusHistoryRepository, never()).findLatestStatuses(anyCollection());
    }

    @Test
    void listForUserAsksOnlyForThatUsersDocuments() {
        Pageable pageable = PageRequest.of(0, 20);

        when(ingestedDocumentRepository.findAllByUserId(userId, pageable))
                .thenReturn(new PageImpl<>(List.of(document(RetrievalScope.USER, userId)), pageable, 1));
        when(statusHistoryRepository.findLatestStatuses(anyCollection())).thenReturn(List.of());

        assertThat(ingestedDocumentService.listForUser(userId, pageable).getContent())
                .singleElement()
                .satisfies(summary -> assertThat(summary.scope()).isEqualTo(RetrievalScope.USER));

        verify(ingestedDocumentRepository).findAllByUserId(userId, pageable);
        verify(ingestedDocumentRepository, never()).findAllGlobal(any());
        verify(ingestedDocumentRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void getGlobalReturnsASummary() {
        when(ingestedDocumentRepository.findGlobalById(documentId))
                .thenReturn(Optional.of(document(RetrievalScope.GLOBAL, null)));
        when(statusHistoryRepository.findByDocumentId(documentId))
                .thenReturn(List.of(DocumentStatus.COMPLETED));

        IngestedDocumentSummary summary = ingestedDocumentService.getGlobal(documentId);

        assertThat(summary.id()).isEqualTo(documentId);
        assertThat(summary.documentStatus()).isEqualTo(DocumentStatus.COMPLETED);

        verify(ingestedDocumentRepository, never()).findById(any());
        verify(ingestedDocumentRepository, never()).findByIdAndUserId(any(), any());
    }

    /**
     * A user-scoped document asked for through the global collection. The repository answers empty
     * because the scope is in its {@code where} clause, and what this pins is that the service turns
     * that into a {@code 404} rather than falling back to an unscoped lookup.
     */
    @Test
    void getGlobalIsNotFoundForADocumentInTheOtherCollection() {
        when(ingestedDocumentRepository.findGlobalById(documentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingestedDocumentService.getGlobal(documentId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(ingestedDocumentRepository, never()).findById(any());
    }

    @Test
    void getForUserIsNotFoundForAnotherUsersDocument() {
        when(ingestedDocumentRepository.findByIdAndUserId(documentId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingestedDocumentService.getForUser(documentId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(ingestedDocumentRepository, never()).findById(any());
        verify(ingestedDocumentRepository, never()).findGlobalById(any());
    }

    @Test
    void deleteGlobalClearsTheChunksBeforeTheRow() {
        IngestedDocument ingestedDocument = document(RetrievalScope.GLOBAL, null);
        VectorDocument vectorDocument = new VectorDocument();

        when(ingestedDocumentRepository.findGlobalById(documentId)).thenReturn(Optional.of(ingestedDocument));
        when(vectorStoreService.findByIngestedDocumentId(documentId)).thenReturn(List.of(vectorDocument));

        ingestedDocumentService.deleteGlobal(documentId);

        verify(vectorStoreService).delete(List.of(vectorDocument));
        verify(statusHistoryRepository).deleteByDocumentId(documentId);
        verify(ingestedDocumentRepository).delete(ingestedDocument);
        verify(ingestedDocumentRepository, never()).findById(any());
        verify(ingestedDocumentRepository, never()).findByIdAndUserId(any(), any());
    }

    /**
     * The chunks outlive the row unless the delete is scoped the same way the read is — a document
     * that cannot be found must not have anything deleted on its behalf.
     */
    @Test
    void deleteForUserTouchesNothingForAnotherUsersDocument() {
        when(ingestedDocumentRepository.findByIdAndUserId(documentId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingestedDocumentService.deleteForUser(documentId, userId))
                .isInstanceOf(ResponseStatusException.class);

        verify(vectorStoreService, never()).delete(anyList());
        verify(ingestedDocumentRepository, never()).delete(any(IngestedDocument.class));
    }

    @Test
    void refreshForUserClearsChunksAndRequeues() {
        IngestedDocument ingestedDocument = document(RetrievalScope.USER, userId);
        ingestedDocument.setFileData(new byte[]{1, 2, 3});
        VectorDocument vectorDocument = new VectorDocument();

        when(ingestedDocumentRepository.findByIdAndUserId(documentId, userId))
                .thenReturn(Optional.of(ingestedDocument));
        when(vectorStoreService.findByIngestedDocumentId(documentId)).thenReturn(List.of(vectorDocument));
        when(ingestedDocumentRepository.save(ingestedDocument)).thenReturn(ingestedDocument);

        IngestedDocumentSummary summary = ingestedDocumentService.refreshForUser(documentId, userId);

        verify(vectorStoreService).delete(List.of(vectorDocument));
        assertThat(summary.documentStatus()).isEqualTo(DocumentStatus.QUEUED);

        verify(ingestedDocumentRepository, never()).findById(any());
        verify(ingestedDocumentRepository, never()).findGlobalById(any());
    }

    @Test
    void renameGlobalWritesTheNewName() {
        IngestedDocument ingestedDocument = document(RetrievalScope.GLOBAL, null);

        when(ingestedDocumentRepository.findGlobalById(documentId)).thenReturn(Optional.of(ingestedDocument));
        when(ingestedDocumentRepository.save(ingestedDocument)).thenReturn(ingestedDocument);
        when(statusHistoryRepository.findByDocumentId(documentId)).thenReturn(List.of(DocumentStatus.COMPLETED));

        IngestedDocumentSummary summary = ingestedDocumentService.renameGlobal(documentId, "onboarding.pdf");

        assertThat(summary.fileName()).isEqualTo("onboarding.pdf");
        assertThat(ingestedDocument.getFileName()).isEqualTo("onboarding.pdf");

        verify(ingestedDocumentRepository, never()).findById(any());
        verify(ingestedDocumentRepository, never()).findByIdAndUserId(any(), any());
    }

    @Test
    void renameRejectsABlankName() {
        assertThatThrownBy(() -> ingestedDocumentService.renameGlobal(documentId, "  "))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(ingestedDocumentRepository, never()).save(any(IngestedDocument.class));
    }

    @Test
    void renameForUserCannotReachAnotherUsersDocument() {
        when(ingestedDocumentRepository.findByIdAndUserId(documentId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingestedDocumentService.renameForUser(documentId, userId, "mine.pdf"))
                .isInstanceOf(ResponseStatusException.class);

        verify(ingestedDocumentRepository, never()).save(any(IngestedDocument.class));
    }

    /**
     * The owner comes from the caller's argument, not the request-scoped user. The path segment the
     * controller already checked is what says whose document this is, and reading the context here
     * would be a second, quieter source of the same fact — which is how the URI ingest path came to
     * record no scope at all.
     */
    @Test
    void queueStampsScopeAndOwner() {
        MockMultipartFile file = new MockMultipartFile("file", FILE_NAME, "application/pdf", new byte[]{1, 2});

        when(ingestedDocumentRepository.save(any(IngestedDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IngestedDocumentSummary queued = ingestedDocumentService.queue(file, RetrievalScope.USER, userId);

        assertThat(queued.scope()).isEqualTo(RetrievalScope.USER);
        assertThat(queued.documentStatus()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(queued.fileName()).isEqualTo(FILE_NAME);

        verify(ingestedDocumentRepository, never()).findGlobalByFileName(any());
    }

    /**
     * De-duplication by file name applies between shared documents only. Two users uploading
     * {@code handbook.pdf} at {@code USER} scope are two documents, and matching the second against
     * the first would hand its uploader someone else's file.
     */
    @Test
    void queueDeduplicatesOnlyAtGlobalScope() {
        MockMultipartFile file = new MockMultipartFile("file", FILE_NAME, "application/pdf", new byte[]{1, 2});
        IngestedDocument existing = document(RetrievalScope.GLOBAL, null);

        when(ingestedDocumentRepository.findGlobalByFileName(FILE_NAME)).thenReturn(Optional.of(existing));

        IngestedDocumentSummary queued = ingestedDocumentService.queue(file, RetrievalScope.GLOBAL, null);

        assertThat(queued.id()).isEqualTo(existing.getId());
        verify(ingestedDocumentRepository, never()).save(any(IngestedDocument.class));
    }

    /**
     * A CHAT document is owned by a conversation, not by the {@code (scope, ownerId)} pair this
     * method takes, so naming the scope here is a mistake rather than a request — queueForChat is
     * the only way in.
     */
    @Test
    void queueRejectsChatScope() {
        MockMultipartFile file = new MockMultipartFile("file", FILE_NAME, "application/pdf", new byte[]{1, 2});

        assertThatThrownBy(() -> ingestedDocumentService.queue(file, RetrievalScope.CHAT, userId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * The conversation goes on the column and into the metadata map both. The column is what the
     * collection's listing and by-id queries read; the key is what DocumentService stamps chunks
     * from and what the teardown queries match, and a row carrying only one of the two would be
     * invisible to half the code that has to find it.
     */
    @Test
    void queueForChatStampsTheConversationOnBothTheColumnAndTheMetadata() {
        UUID chatId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", FILE_NAME, "application/pdf", new byte[]{1, 2});

        when(ingestedDocumentRepository.save(any(IngestedDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IngestedDocumentSummary queued = ingestedDocumentService.queueForChat(file, chatId, userId);

        ArgumentCaptor<IngestedDocument> savedCaptor = ArgumentCaptor.forClass(IngestedDocument.class);
        verify(ingestedDocumentRepository).save(savedCaptor.capture());
        IngestedDocument saved = savedCaptor.getValue();

        assertThat(saved.getScope()).isEqualTo(RetrievalScope.CHAT);
        assertThat(saved.getChatId()).isEqualTo(chatId);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getMetadata()).containsEntry(IngestedDocument.CHAT_ID, chatId.toString());
        assertThat(queued.scope()).isEqualTo(RetrievalScope.CHAT);
        assertThat(queued.chatId()).isEqualTo(chatId);
    }

    /**
     * Unlike an attachment-sourced row, this one is the only copy of the bytes — there is no
     * chat_attachment behind it — which is also what lets refresh re-run over it. It goes in QUEUED
     * because nobody is waiting on a turn for it, so the ordinary poller does the work.
     */
    @Test
    void queueForChatStoresTheBytesAndQueuesForThePoller() {
        MockMultipartFile file = new MockMultipartFile("file", FILE_NAME, "application/pdf", new byte[]{1, 2});

        when(ingestedDocumentRepository.save(any(IngestedDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ingestedDocumentService.queueForChat(file, UUID.randomUUID(), userId);

        ArgumentCaptor<IngestedDocument> savedCaptor = ArgumentCaptor.forClass(IngestedDocument.class);
        verify(ingestedDocumentRepository).save(savedCaptor.capture());
        IngestedDocument saved = savedCaptor.getValue();

        assertThat(saved.getFileData()).isNotEmpty();
        assertThat(saved.getDocumentStatus()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(saved.getDocumentSource()).isEqualTo(DocumentSource.USER);
    }

    /**
     * The conversation is in the where clause, so a document of another chat is absent rather than
     * fetched and then rejected — the same shape the user collection uses for its owner.
     */
    @Test
    void aDocumentOfAnotherChatIsNotFound() {
        UUID chatId = UUID.randomUUID();

        when(ingestedDocumentRepository.findByIdAndChatId(documentId, chatId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingestedDocumentService.getForChat(documentId, chatId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(ingestedDocumentRepository, never()).delete(any(IngestedDocument.class));
    }

    /**
     * Deleting a conversation's document takes its chunks with it, and leaves the chat_attachment
     * row it may have arrived on alone — that row is what the message displays, and removing it
     * would rewrite history the user did not ask to change.
     */
    @Test
    void deleteForChatClearsTheChunksAndLeavesTheAttachmentAlone() {
        UUID chatId = UUID.randomUUID();
        IngestedDocument ingestedDocument = chatDocument(documentId);
        VectorDocument chunk = new VectorDocument();

        when(ingestedDocumentRepository.findByIdAndChatId(documentId, chatId))
                .thenReturn(Optional.of(ingestedDocument));
        when(vectorStoreService.findByIngestedDocumentId(documentId)).thenReturn(List.of(chunk));

        ingestedDocumentService.deleteForChat(documentId, chatId);

        verify(vectorStoreService).delete(List.of(chunk));
        verify(statusHistoryRepository).deleteByDocumentId(documentId);
        verify(ingestedDocumentRepository).delete(ingestedDocument);
    }

    /**
     * The chat path creates its row and begins work in the same call, so nothing ever waits on it.
     * A row that passed through QUEUED would be picked up by StatusHistoryService.processQueued and
     * ingested a second time by the poller.
     */
    @Test
    void beginChatIngestionCreatesAnInProgressChatScopedRow() {
        UUID chatId = UUID.randomUUID();
        UUID chatAttachmentId = UUID.randomUUID();

        when(ingestedDocumentRepository.save(any(IngestedDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IngestedDocument ingestedDocument = ingestedDocumentService.beginChatIngestion(
                chatId, userId, chatAttachmentId, FILE_NAME, "application/pdf");

        assertThat(ingestedDocument.getScope()).isEqualTo(RetrievalScope.CHAT);
        assertThat(ingestedDocument.getDocumentStatus()).isEqualTo(DocumentStatus.IN_PROGRESS);
        assertThat(ingestedDocument.getUserId()).isEqualTo(userId);
        assertThat(ingestedDocument.getDocumentSource()).isEqualTo(DocumentSource.CHAT);
        assertThat(ingestedDocument.getFileName()).isEqualTo(FILE_NAME);
        assertThat(ingestedDocument.getContentType()).isEqualTo("application/pdf");
        assertThat(ingestedDocument.getCreated()).isNotNull();
    }

    /**
     * The bytes already live on the chat_attachment row. A second copy here would double the
     * storage and be free to drift from the one the attachment itself serves.
     */
    @Test
    void beginChatIngestionStoresNoFileBytes() {
        when(ingestedDocumentRepository.save(any(IngestedDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IngestedDocument ingestedDocument = ingestedDocumentService.beginChatIngestion(
                UUID.randomUUID(), userId, UUID.randomUUID(), FILE_NAME, "application/pdf");

        assertThat(ingestedDocument.getFileData()).isEmpty();
    }

    /**
     * Both ids go on as strings, so a later lookup by either one compares against what the JSON
     * metadata column actually holds.
     */
    @Test
    void beginChatIngestionStampsTheChatAndAttachmentIds() {
        UUID chatId = UUID.randomUUID();
        UUID chatAttachmentId = UUID.randomUUID();

        when(ingestedDocumentRepository.save(any(IngestedDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IngestedDocument ingestedDocument = ingestedDocumentService.beginChatIngestion(
                chatId, userId, chatAttachmentId, FILE_NAME, "application/pdf");

        assertThat(ingestedDocument.getMetadata())
                .containsEntry(IngestedDocument.CHAT_ID, chatId.toString())
                .containsEntry(IngestedDocument.CHAT_ATTACHMENT_ID, chatAttachmentId.toString())
                .containsEntry(IngestedDocument.ORIGINAL_FILE_NAME, FILE_NAME);
    }

    /**
     * One status_history row, written at IN_PROGRESS. Saving at QUEUED and updating straight after
     * would write two, and leave the row briefly visible to findQueued.
     */
    @Test
    void beginChatIngestionWritesOneStatusHistoryRowAndNeverQueued() {
        when(ingestedDocumentRepository.save(any(IngestedDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ingestedDocumentService.beginChatIngestion(
                UUID.randomUUID(), userId, UUID.randomUUID(), FILE_NAME, "application/pdf");

        ArgumentCaptor<StatusHistory> statusHistoryCaptor = ArgumentCaptor.forClass(StatusHistory.class);

        verify(statusHistoryRepository).save(statusHistoryCaptor.capture());

        assertThat(statusHistoryCaptor.getAllValues())
                .singleElement()
                .satisfies(statusHistory ->
                        assertThat(statusHistory.getDocumentStatus()).isEqualTo(DocumentStatus.IN_PROGRESS));

        verify(ingestedDocumentRepository).save(any(IngestedDocument.class));
    }

    /**
     * The row a chat attachment left behind is found by the id {@code beginChatIngestion} stamped
     * into its metadata, and torn down through the same three steps a GLOBAL/USER delete uses —
     * chunks, status history, row. Nothing here duplicates that teardown.
     */
    @Test
    void deleteByChatAttachmentIdClearsChunksStatusHistoryAndRow() {
        UUID chatAttachmentId = UUID.randomUUID();
        IngestedDocument ingestedDocument = chatDocument(documentId);
        VectorDocument vectorDocument = new VectorDocument();

        when(ingestedDocumentRepository.findByChatAttachmentId(chatAttachmentId.toString()))
                .thenReturn(List.of(ingestedDocument));
        when(vectorStoreService.findByIngestedDocumentId(documentId)).thenReturn(List.of(vectorDocument));

        ingestedDocumentService.deleteByChatAttachmentId(chatAttachmentId);

        verify(vectorStoreService).delete(List.of(vectorDocument));
        verify(statusHistoryRepository).deleteByDocumentId(documentId);
        verify(ingestedDocumentRepository).delete(ingestedDocument);
    }

    /**
     * A conversation with several attached documents leaves several rows behind. Deleting only the
     * first match is the leak this method exists to close.
     */
    @Test
    void deleteByChatIdDeletesEveryDocumentOfTheChat() {
        UUID chatId = UUID.randomUUID();
        IngestedDocument first = chatDocument(UUID.randomUUID());
        IngestedDocument second = chatDocument(UUID.randomUUID());

        when(ingestedDocumentRepository.findByChatId(chatId.toString()))
                .thenReturn(List.of(first, second));
        when(vectorStoreService.findByIngestedDocumentId(any())).thenReturn(List.of());

        ingestedDocumentService.deleteByChatId(chatId);

        verify(statusHistoryRepository).deleteByDocumentId(first.getId());
        verify(statusHistoryRepository).deleteByDocumentId(second.getId());
        verify(ingestedDocumentRepository).delete(first);
        verify(ingestedDocumentRepository).delete(second);
    }

    /**
     * An image-only attachment, or a document turned away before any row was opened. The lookup
     * answers empty and nothing is deleted on its behalf.
     */
    @Test
    void deleteByChatAttachmentIdTouchesNothingWhenNoRowExists() {
        UUID chatAttachmentId = UUID.randomUUID();

        when(ingestedDocumentRepository.findByChatAttachmentId(chatAttachmentId.toString()))
                .thenReturn(List.of());

        ingestedDocumentService.deleteByChatAttachmentId(chatAttachmentId);

        verify(vectorStoreService, never()).delete(anyList());
        verify(statusHistoryRepository, never()).deleteByDocumentId(any());
        verify(ingestedDocumentRepository, never()).delete(any(IngestedDocument.class));
    }

    /**
     * Cleanup cannot depend on the row having reached COMPLETED. A delete racing the ingest, or a
     * document that failed extraction, leaves a row in some other status that still has to go.
     */
    @Test
    void deleteByChatAttachmentIdRemovesARowLeftMidIngestion() {
        UUID chatAttachmentId = UUID.randomUUID();
        IngestedDocument ingestedDocument = chatDocument(documentId);
        ingestedDocument.setDocumentStatus(DocumentStatus.IN_PROGRESS);

        when(ingestedDocumentRepository.findByChatAttachmentId(chatAttachmentId.toString()))
                .thenReturn(List.of(ingestedDocument));
        when(vectorStoreService.findByIngestedDocumentId(documentId)).thenReturn(List.of());

        ingestedDocumentService.deleteByChatAttachmentId(chatAttachmentId);

        verify(ingestedDocumentRepository).delete(ingestedDocument);
    }

    private IngestedDocument chatDocument(UUID id) {
        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(id);
        ingestedDocument.setFileName(FILE_NAME);
        ingestedDocument.setContentType("application/pdf");
        ingestedDocument.setDocumentSource(DocumentSource.CHAT);
        ingestedDocument.setScope(RetrievalScope.CHAT);
        ingestedDocument.setUserId(userId);

        return ingestedDocument;
    }
}
