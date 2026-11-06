package com.solesonic.service.etl;

import com.solesonic.exception.rag.DocumentReadException;
import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentEvent;
import com.solesonic.model.chat.attachment.ChatAttachmentIndexState;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.service.ingestion.IngestedDocumentService;
import com.solesonic.service.rag.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.Resource;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.solesonic.model.chat.attachment.ExtractionFailureReason.DOCUMENT_TOO_LARGE;
import static com.solesonic.model.chat.attachment.ExtractionFailureReason.DOCUMENT_UNREADABLE;
import static com.solesonic.model.chat.attachment.ExtractionFailureReason.EMBEDDING_UNAVAILABLE;
import static com.solesonic.model.chat.attachment.ExtractionFailureReason.EXCEEDED_DOCUMENT_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The send path and the heal path share one {@code ingestOne}, so every guarantee here holds for
 * both: an attachment is re-indexed exactly when it is not detached and has no retrievable chunk,
 * never by two workers at once, and never into a second set of rows.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatDocumentIngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final Duration STALE_LEASE = Duration.ofMinutes(15);

    @Mock
    private ChatAttachmentService chatAttachmentService;

    @Mock
    private DocumentService documentService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private IngestedDocumentService ingestedDocumentService;

    @Mock
    private VectorStoreService vectorStoreService;

    private ChatDocumentIngestionService chatDocumentIngestionService;

    private UUID chatId;
    private UUID userId;

    @BeforeEach
    void beforeEach() {
        chatDocumentIngestionService = service(Duration.ZERO);

        chatId = UUID.randomUUID();
        userId = UUID.randomUUID();

        when(chatAttachmentService.claimIndexing(any(), any(), any(), any())).thenReturn(true);
        when(chatAttachmentService.saveChunkCountUnderLease(any(), anyInt(), any())).thenReturn(true);
    }

    private ChatDocumentIngestionService service(Duration leaseWait) {
        return new ChatDocumentIngestionService(
                chatAttachmentService,
                documentService,
                notificationService,
                ingestedDocumentService,
                vectorStoreService,
                DataSize.ofMegabytes(10),
                STALE_LEASE,
                leaseWait,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ZonedDateTime now() {
        return NOW.atZone(ZoneOffset.UTC);
    }

    private ChatAttachment attachment(String fileName, long fileSizeBytes) {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(UUID.randomUUID());
        attachment.setUserId(userId);
        attachment.setChatId(chatId);
        attachment.setFileName(fileName);
        attachment.setContentType("application/pdf");
        attachment.setFileData("hello".getBytes());
        attachment.setFileSizeBytes(fileSizeBytes);

        return attachment;
    }

    /**
     * Stubs what a fresh read of the attachment's lifecycle returns.
     */
    private void stored(ChatAttachment attachment, Integer chunkCount, boolean detached, ZonedDateTime claimedAt) {
        when(chatAttachmentService.indexState(attachment.getId())).thenReturn(Optional.of(new ChatAttachmentIndexState(
                attachment.getId(), attachment.getUserId(), attachment.getChatId(), attachment.getContentType(),
                attachment.getFileSizeBytes(), chunkCount, null, detached, claimedAt, null)));
    }

    private void sent(ChatAttachment... attachments) {
        Set<UUID> ids = new LinkedHashSet<>();

        for (ChatAttachment attachment : attachments) {
            ids.add(attachment.getId());
        }

        when(chatAttachmentService.attachments(userId, ids)).thenReturn(List.of(attachments));
    }

    private IngestedDocument row(DocumentStatus documentStatus, ZonedDateTime updated) {
        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(UUID.randomUUID());
        ingestedDocument.setDocumentStatus(documentStatus);
        ingestedDocument.setUpdated(updated);

        return ingestedDocument;
    }

    private UUID opensRow(ChatAttachment attachment, int chunkCount) {
        UUID ingestedDocumentId = UUID.randomUUID();
        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(ingestedDocumentId);

        when(ingestedDocumentService.beginChatIngestion(chatId, userId, attachment.getId(),
                attachment.getFileName(), attachment.getContentType()))
                .thenReturn(ingestedDocument);
        when(documentService.resourceToVectorStore(any(Resource.class), eq(ingestedDocumentId)))
                .thenReturn(chunkCount);

        return ingestedDocumentId;
    }

    private List<String> ingest(ChatAttachment attachment) {
        return chatDocumentIngestionService.ingest(chatId, userId, Set.of(attachment.getId()));
    }

    // ---- the send path -------------------------------------------------------------------------

    /**
     * The happy path: lease, pre-clean, open the tracked row, delegate the pipeline, record the
     * count, release. Pre-clean runs before the row is opened, which is what makes a retry safe.
     */
    @Test
    void test_ingest_claims_precleans_indexes_and_releases() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, null, false, null);
        opensRow(attachment, 3);

        List<String> indexed = ingest(attachment);

        assertThat(indexed).containsExactly("notes.pdf");

        InOrder inOrder = inOrder(chatAttachmentService, ingestedDocumentService, vectorStoreService, documentService);
        inOrder.verify(chatAttachmentService).claimIndexing(attachment.getId(), now(), now().minus(STALE_LEASE), null);
        inOrder.verify(ingestedDocumentService).deleteByChatAttachmentId(attachment.getId());
        inOrder.verify(vectorStoreService).deleteByChatAttachmentId(attachment.getId());
        inOrder.verify(ingestedDocumentService)
                .beginChatIngestion(chatId, userId, attachment.getId(), "notes.pdf", "application/pdf");
        inOrder.verify(documentService).resourceToVectorStore(any(Resource.class), any());
        inOrder.verify(chatAttachmentService).saveChunkCountUnderLease(eq(attachment.getId()), eq(3), any());
        inOrder.verify(chatAttachmentService).releaseIndexing(eq(attachment.getId()), any());

        verify(notificationService).emitAttachment(chatId, ChatAttachmentEvent.indexed(chatId, attachment.getId(), 3));
    }

    @Test
    void test_ingest_maps_document_read_exception_to_document_unreadable_and_releases() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, null, false, null);
        UUID ingestedDocumentId = opensRow(attachment, 0);
        when(documentService.resourceToVectorStore(any(Resource.class), eq(ingestedDocumentId)))
                .thenThrow(new DocumentReadException("no readable text"));

        List<String> indexed = ingest(attachment);

        assertThat(indexed).isEmpty();

        verify(chatAttachmentService).saveExtractionFailureUnderLease(eq(attachment.getId()), eq(DOCUMENT_UNREADABLE), any());
        verify(notificationService).emitAttachment(chatId,
                ChatAttachmentEvent.notIndexed(chatId, attachment.getId(), DOCUMENT_UNREADABLE));
        verify(chatAttachmentService, never()).saveChunkCountUnderLease(any(), anyInt(), any());
        verify(chatAttachmentService).releaseIndexing(eq(attachment.getId()), any());
    }

    @Test
    void test_ingest_maps_other_runtime_exception_to_embedding_unavailable_and_releases() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, null, false, null);
        UUID ingestedDocumentId = opensRow(attachment, 0);
        when(documentService.resourceToVectorStore(any(Resource.class), eq(ingestedDocumentId)))
                .thenThrow(new RuntimeException("vector store unavailable"));

        List<String> indexed = ingest(attachment);

        assertThat(indexed).isEmpty();

        verify(chatAttachmentService).saveExtractionFailureUnderLease(eq(attachment.getId()), eq(EMBEDDING_UNAVAILABLE), any());
        verify(notificationService).emitAttachment(chatId,
                ChatAttachmentEvent.notIndexed(chatId, attachment.getId(), EMBEDDING_UNAVAILABLE));
        verify(chatAttachmentService).releaseIndexing(eq(attachment.getId()), any());
    }

    /**
     * Turned away before extraction, so it never claims, never pre-cleans and never opens a row.
     */
    @Test
    void test_ingest_rejects_oversized_attachment_without_claiming_or_opening_a_row() {
        ChatAttachment attachment = attachment("huge.pdf", DataSize.ofMegabytes(20).toBytes());
        sent(attachment);
        stored(attachment, null, false, null);

        List<String> indexed = ingest(attachment);

        assertThat(indexed).isEmpty();

        verify(chatAttachmentService).saveExtractionFailure(attachment.getId(), DOCUMENT_TOO_LARGE);
        verify(chatAttachmentService, never()).claimIndexing(any(), any(), any(), any());
        verifyNoInteractions(documentService);
        verifyNoInteractions(ingestedDocumentService);
    }

    /**
     * An attachment whose row still has chunks is reported indexed and never re-embedded.
     */
    @Test
    void test_ingest_short_circuits_a_healthy_attachment() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, 7, false, null);
        IngestedDocument row = row(DocumentStatus.COMPLETED, now().minusDays(3));
        when(ingestedDocumentService.findByChatAttachmentId(attachment.getId())).thenReturn(List.of(row));
        when(vectorStoreService.hasChunks(row.getId())).thenReturn(true);

        List<String> indexed = ingest(attachment);

        assertThat(indexed).containsExactly("notes.pdf");

        verifyNoInteractions(documentService);
        verify(chatAttachmentService, never()).claimIndexing(any(), any(), any(), any());
        verify(ingestedDocumentService, never()).beginChatIngestion(any(), any(), any(), any(), any());
        verify(notificationService).emitAttachment(chatId, ChatAttachmentEvent.indexed(chatId, attachment.getId(), 7));
    }

    /**
     * F1. {@code V3_27} wiped every chunk and row but left {@code chunk_count}; trusting the count
     * kept those documents unretrievable forever. A count with nothing behind it must be re-indexed.
     */
    @Test
    void test_ingest_reindexes_an_attachment_whose_chunk_count_has_nothing_behind_it() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, 7, false, null);
        when(ingestedDocumentService.findByChatAttachmentId(attachment.getId())).thenReturn(List.of());
        opensRow(attachment, 4);

        List<String> indexed = ingest(attachment);

        assertThat(indexed).containsExactly("notes.pdf");

        verify(chatAttachmentService).claimIndexing(any(), any(), any(), any());
        verify(documentService).resourceToVectorStore(any(Resource.class), any());
        verify(chatAttachmentService).saveChunkCountUnderLease(eq(attachment.getId()), eq(4), any());
        verify(notificationService).emitAttachment(chatId, ChatAttachmentEvent.indexed(chatId, attachment.getId(), 4));
    }

    /**
     * A row whose chunks are gone is just as broken as no row at all.
     */
    @Test
    void test_ingest_reindexes_an_attachment_whose_completed_row_lost_its_chunks() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, 7, false, null);
        IngestedDocument row = row(DocumentStatus.COMPLETED, now().minusDays(3));
        when(ingestedDocumentService.findByChatAttachmentId(attachment.getId())).thenReturn(List.of(row));
        when(vectorStoreService.hasChunks(row.getId())).thenReturn(false);
        opensRow(attachment, 4);

        ingest(attachment);

        verify(chatAttachmentService).saveChunkCountUnderLease(eq(attachment.getId()), eq(4), any());
    }

    /**
     * A detached attachment left the conversation on purpose. Re-indexing it would duplicate a
     * promoted document or resurrect a removed one, so it is never touched — not even looked up.
     */
    @Test
    void test_ingest_never_touches_a_detached_attachment() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, 7, true, null);

        ingest(attachment);

        verify(chatAttachmentService, never()).claimIndexing(any(), any(), any(), any());
        verifyNoInteractions(ingestedDocumentService);
        verifyNoInteractions(documentService);
        verify(notificationService).emitAttachment(chatId, ChatAttachmentEvent.indexed(chatId, attachment.getId(), 7));
    }

    /**
     * A refresh deletes the chunks and re-queues the row; the poller is about to re-add them. That
     * looks broken but is not, and re-indexing it would race the poller.
     */
    @Test
    void test_ingest_leaves_a_queued_refresh_alone() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, 7, false, null);
        IngestedDocument row = row(DocumentStatus.QUEUED, now().minusMinutes(1));
        when(ingestedDocumentService.findByChatAttachmentId(attachment.getId())).thenReturn(List.of(row));

        ingest(attachment);

        verify(chatAttachmentService, never()).claimIndexing(any(), any(), any(), any());
        verifyNoInteractions(documentService);
    }

    @Test
    void test_ingest_leaves_a_fresh_in_progress_row_alone() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, 7, false, null);
        IngestedDocument row = row(DocumentStatus.KEYWORD_ENRICHING, now().minusMinutes(2));
        when(ingestedDocumentService.findByChatAttachmentId(attachment.getId())).thenReturn(List.of(row));

        ingest(attachment);

        verify(chatAttachmentService, never()).claimIndexing(any(), any(), any(), any());
        verifyNoInteractions(documentService);
    }

    /**
     * An in-progress row older than the stale cutoff belongs to a worker that died; waiting on it
     * would block healing forever.
     */
    @Test
    void test_ingest_reindexes_a_stranded_in_progress_row() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, 7, false, null);
        IngestedDocument row = row(DocumentStatus.IN_PROGRESS, now().minusHours(1));
        when(ingestedDocumentService.findByChatAttachmentId(attachment.getId())).thenReturn(List.of(row));
        opensRow(attachment, 2);

        ingest(attachment);

        verify(chatAttachmentService).saveChunkCountUnderLease(eq(attachment.getId()), eq(2), any());
    }

    /**
     * F5. Vectors were saved but recording the count failed, so the next attempt sees no count. It
     * used to open a second row and embed a second set of chunks next to the first. The pre-clean
     * under the lease removes the first attempt's rows and chunks before anything new is written.
     */
    @Test
    void test_retry_after_a_failed_count_save_precleans_before_writing_again() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, null, false, null);
        opensRow(attachment, 3);
        when(chatAttachmentService.saveChunkCountUnderLease(eq(attachment.getId()), eq(3), any()))
                .thenThrow(new RuntimeException("connection reset"))
                .thenReturn(true);

        List<String> firstAttempt = ingest(attachment);
        List<String> secondAttempt = ingest(attachment);

        assertThat(firstAttempt).isEmpty();
        assertThat(secondAttempt).containsExactly("notes.pdf");

        InOrder inOrder = inOrder(ingestedDocumentService, vectorStoreService);
        for (int attempt = 0; attempt < 2; attempt++) {
            inOrder.verify(ingestedDocumentService).deleteByChatAttachmentId(attachment.getId());
            inOrder.verify(vectorStoreService).deleteByChatAttachmentId(attachment.getId());
            inOrder.verify(ingestedDocumentService).beginChatIngestion(any(), any(), any(), any(), any());
        }

        verify(chatAttachmentService, times(2)).releaseIndexing(eq(attachment.getId()), any());
    }

    /**
     * Another worker holds the lease and does not finish in time: this turn goes ahead without the
     * document rather than waiting, failing, or indexing it a second time — and records no failure,
     * because nothing failed.
     */
    @Test
    void test_ingest_skips_when_another_worker_holds_the_lease() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, null, false, now().minusMinutes(1));
        when(chatAttachmentService.claimIndexing(any(), any(), any(), any())).thenReturn(false);

        List<String> indexed = ingest(attachment);

        assertThat(indexed).isEmpty();

        verifyNoInteractions(documentService);
        verify(ingestedDocumentService, never()).beginChatIngestion(any(), any(), any(), any(), any());
        verify(chatAttachmentService, never()).saveExtractionFailure(any(), any());
        verify(chatAttachmentService, never()).saveExtractionFailureUnderLease(any(), any(), any());
        verify(chatAttachmentService, never()).releaseIndexing(any(), any());
        verify(notificationService).emitAttachment(chatId,
                ChatAttachmentEvent.notIndexed(chatId, attachment.getId(), EMBEDDING_UNAVAILABLE));
    }

    /**
     * The other worker finished while this one waited: the fresh read now finds it healthy and
     * reports its count instead of indexing again.
     */
    @Test
    void test_ingest_waits_for_a_lease_and_then_trusts_the_other_workers_result() {
        chatDocumentIngestionService = service(Duration.ofMillis(200));

        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        IngestedDocument row = row(DocumentStatus.COMPLETED, now());

        ChatAttachmentIndexState leased = new ChatAttachmentIndexState(attachment.getId(), userId, chatId,
                "application/pdf", 100, null, null, false, now().minusSeconds(5), now().minusSeconds(5));
        ChatAttachmentIndexState done = new ChatAttachmentIndexState(attachment.getId(), userId, chatId,
                "application/pdf", 100, 6, null, false, null, now().minusSeconds(5));

        when(chatAttachmentService.indexState(attachment.getId()))
                .thenReturn(Optional.of(leased), Optional.of(done));
        when(chatAttachmentService.claimIndexing(any(), any(), any(), any())).thenReturn(false);
        when(ingestedDocumentService.findByChatAttachmentId(attachment.getId())).thenReturn(List.of(row));
        when(vectorStoreService.hasChunks(row.getId())).thenReturn(true);

        List<String> indexed = ingest(attachment);

        assertThat(indexed).containsExactly("notes.pdf");

        verifyNoInteractions(documentService);
        verify(notificationService).emitAttachment(chatId, ChatAttachmentEvent.indexed(chatId, attachment.getId(), 6));
    }

    /**
     * The chat was deleted while the document was being embedded. Its deleter has already run, so
     * the rows and chunks written after it would leak with nothing left to remove them.
     */
    @Test
    void test_ingest_cleans_up_when_the_attachment_vanished_mid_ingest() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, null, false, null);
        opensRow(attachment, 3);
        ChatAttachmentIndexState unindexed = new ChatAttachmentIndexState(attachment.getId(), userId, chatId,
                "application/pdf", 100, null, null, false, null, null);
        when(chatAttachmentService.indexState(attachment.getId()))
                .thenReturn(Optional.of(unindexed), Optional.empty());

        List<String> indexed = ingest(attachment);

        assertThat(indexed).isEmpty();

        verify(ingestedDocumentService, times(2)).deleteByChatAttachmentId(attachment.getId());
        verify(vectorStoreService, times(2)).deleteByChatAttachmentId(attachment.getId());
        verify(chatAttachmentService).releaseIndexing(eq(attachment.getId()), any());
    }

    /**
     * Attachments beyond {@code MAX_DOCUMENTS_PER_MESSAGE} are turned away before extraction starts,
     * so they leave no row behind.
     */
    @Test
    void test_ingest_rejects_attachments_beyond_the_per_message_limit_without_opening_a_row() {
        List<ChatAttachment> attachments = new ArrayList<>();

        for (int index = 0; index <= ChatDocumentIngestionService.MAX_DOCUMENTS_PER_MESSAGE; index++) {
            ChatAttachment attachment = attachment("notes-" + index + ".pdf", 100);
            stored(attachment, 1, true, null);
            attachments.add(attachment);
        }

        sent(attachments.toArray(ChatAttachment[]::new));

        chatDocumentIngestionService.ingest(chatId, userId,
                new LinkedHashSet<>(attachments.stream().map(ChatAttachment::getId).toList()));

        ChatAttachment beyondLimit = attachments.get(ChatDocumentIngestionService.MAX_DOCUMENTS_PER_MESSAGE);

        verify(chatAttachmentService).saveExtractionFailure(beyondLimit.getId(), EXCEEDED_DOCUMENT_LIMIT);
        verifyNoInteractions(documentService);
        verifyNoInteractions(ingestedDocumentService);
    }

    /**
     * Review finding M2. Removing a document from the conversation while it is still being embedded
     * tombstones the attachment and deletes the rows that existed then. Without a post-check, the
     * chunks written afterwards would make the removed document searchable again.
     */
    @Test
    void test_ingest_discards_its_work_when_the_attachment_is_detached_mid_ingest() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        opensRow(attachment, 3);
        ChatAttachmentIndexState before = new ChatAttachmentIndexState(attachment.getId(), userId, chatId,
                "application/pdf", 100, null, null, false, null, null);
        ChatAttachmentIndexState detachedMidway = new ChatAttachmentIndexState(attachment.getId(), userId, chatId,
                "application/pdf", 100, null, null, true, now(), now());
        when(chatAttachmentService.indexState(attachment.getId()))
                .thenReturn(Optional.of(before), Optional.of(detachedMidway));

        List<String> indexed = ingest(attachment);

        assertThat(indexed).isEmpty();

        verify(ingestedDocumentService, times(2)).deleteByChatAttachmentId(attachment.getId());
        verify(vectorStoreService, times(2)).deleteByChatAttachmentId(attachment.getId());
        verify(chatAttachmentService, never()).saveChunkCountUnderLease(any(), anyInt(), any());
    }

    /**
     * Review finding M1. A worker that outlived the stale cutoff lost its lease to another; its final
     * write is refused and it reports nothing as indexed, leaving the new holder's result to stand.
     */
    @Test
    void test_ingest_reports_nothing_when_the_lease_was_taken_over_before_it_finished() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, null, false, null);
        opensRow(attachment, 3);
        when(chatAttachmentService.saveChunkCountUnderLease(any(), anyInt(), any())).thenReturn(false);

        List<String> indexed = ingest(attachment);

        assertThat(indexed).isEmpty();

        // No cleanup: the chunks now belong to the worker holding the lease.
        verify(ingestedDocumentService, times(1)).deleteByChatAttachmentId(attachment.getId());
        verify(notificationService).emitAttachment(chatId,
                ChatAttachmentEvent.notIndexed(chatId, attachment.getId(), EMBEDDING_UNAVAILABLE));
    }

    /**
     * Review finding M1: the lease token is what release and the final write match on, so it must be
     * exactly the value claimed — at the column's microsecond precision.
     */
    @Test
    void test_release_and_final_write_use_the_claim_token() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, null, false, null);
        opensRow(attachment, 3);

        ingest(attachment);

        verify(chatAttachmentService).claimIndexing(attachment.getId(), now(), now().minus(STALE_LEASE), null);
        verify(chatAttachmentService).saveChunkCountUnderLease(attachment.getId(), 3, now());
        verify(chatAttachmentService).releaseIndexing(attachment.getId(), now());
    }

    /**
     * Review finding M3. Between this worker's health check and its claim, another worker claimed,
     * re-indexed and released. The claim is conditional on the attempt this worker observed, so it
     * fails; the decision is then made again from fresh state, which is healthy — nothing is redone.
     */
    @Test
    void test_a_claim_lost_to_a_finished_worker_re_decides_instead_of_redoing_it() {
        chatDocumentIngestionService = service(Duration.ofMillis(200));

        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        IngestedDocument row = row(DocumentStatus.COMPLETED, now());

        ChatAttachmentIndexState seen = new ChatAttachmentIndexState(attachment.getId(), userId, chatId,
                "application/pdf", 100, 7, null, false, null, now().minusDays(9));
        ChatAttachmentIndexState healedMeanwhile = new ChatAttachmentIndexState(attachment.getId(), userId, chatId,
                "application/pdf", 100, 5, null, false, null, now().minusSeconds(1));

        when(chatAttachmentService.indexState(attachment.getId()))
                .thenReturn(Optional.of(seen), Optional.of(healedMeanwhile));
        when(ingestedDocumentService.findByChatAttachmentId(attachment.getId()))
                .thenReturn(List.of(), List.of(row));
        when(vectorStoreService.hasChunks(row.getId())).thenReturn(true);
        when(chatAttachmentService.claimIndexing(any(), any(), any(), any())).thenReturn(false);

        List<String> indexed = ingest(attachment);

        assertThat(indexed).containsExactly("notes.pdf");

        verify(chatAttachmentService, times(1)).claimIndexing(eq(attachment.getId()), any(), any(), eq(now().minusDays(9)));
        verify(ingestedDocumentService, never()).deleteByChatAttachmentId(any());
        verifyNoInteractions(documentService);
        verify(notificationService).emitAttachment(chatId, ChatAttachmentEvent.indexed(chatId, attachment.getId(), 5));
    }

    /**
     * A detached attachment re-sent keeps its event (its history says indexed), but it is not named to
     * the model as retrievable: in this conversation it is not.
     */
    @Test
    void test_a_resent_detached_attachment_is_not_named_as_retrievable() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        sent(attachment);
        stored(attachment, 7, true, null);

        List<String> indexed = ingest(attachment);

        assertThat(indexed).isEmpty();
        verify(notificationService).emitAttachment(chatId, ChatAttachmentEvent.indexed(chatId, attachment.getId(), 7));
    }

    /**
     * Security review: one wait budget per send. Several attachments all leased elsewhere wait out
     * the budget once between them, not once each.
     */
    @Test
    void test_one_send_waits_for_leases_at_most_once_in_total() {
        chatDocumentIngestionService = service(Duration.ofMillis(300));

        List<ChatAttachment> attachments = new ArrayList<>();

        for (int index = 0; index < 3; index++) {
            ChatAttachment attachment = attachment("notes-" + index + ".pdf", 100);
            stored(attachment, null, false, now().minusSeconds(1));
            attachments.add(attachment);
        }

        sent(attachments.toArray(ChatAttachment[]::new));
        when(chatAttachmentService.claimIndexing(any(), any(), any(), any())).thenReturn(false);

        long started = System.nanoTime();

        chatDocumentIngestionService.ingest(chatId, userId,
                new LinkedHashSet<>(attachments.stream().map(ChatAttachment::getId).toList()));

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMillis).isLessThan(800);
    }

    // ---- the heal path -------------------------------------------------------------------------

    /**
     * Healing runs after the turn's stream may have closed, so it emits nothing at all.
     */
    @Test
    void test_heal_indexes_without_emitting_any_event() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        stored(attachment, null, false, null);
        when(chatAttachmentService.forIndexing(attachment.getId())).thenReturn(Optional.of(attachment));
        opensRow(attachment, 5);

        boolean healed = chatDocumentIngestionService.heal(chatId, userId, attachment.getId());

        assertThat(healed).isTrue();

        verify(chatAttachmentService).saveChunkCountUnderLease(eq(attachment.getId()), eq(5), any());
        verify(chatAttachmentService).releaseIndexing(eq(attachment.getId()), any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void test_heal_refuses_an_attachment_of_another_chat() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        stored(attachment, null, false, null);

        boolean healed = chatDocumentIngestionService.heal(UUID.randomUUID(), userId, attachment.getId());

        assertThat(healed).isFalse();

        verify(chatAttachmentService, never()).claimIndexing(any(), any(), any(), any());
        verifyNoInteractions(documentService);
    }

    @Test
    void test_heal_never_touches_a_detached_attachment() {
        ChatAttachment attachment = attachment("notes.pdf", 100);
        stored(attachment, 7, true, null);
        when(chatAttachmentService.forIndexing(attachment.getId())).thenReturn(Optional.of(attachment));

        chatDocumentIngestionService.heal(chatId, userId, attachment.getId());

        verify(chatAttachmentService, never()).claimIndexing(any(), any(), any(), any());
        verifyNoInteractions(documentService);
    }

    /**
     * Java review L5: the healer has two threads and never waits on a lease someone else holds.
     */
    @Test
    void test_heal_never_waits_on_a_held_lease() {
        chatDocumentIngestionService = service(Duration.ofSeconds(30));

        ChatAttachment attachment = attachment("notes.pdf", 100);
        stored(attachment, null, false, now().minusSeconds(1));
        when(chatAttachmentService.forIndexing(attachment.getId())).thenReturn(Optional.of(attachment));
        when(chatAttachmentService.claimIndexing(any(), any(), any(), any())).thenReturn(false);

        long started = System.nanoTime();

        boolean healed = chatDocumentIngestionService.heal(chatId, userId, attachment.getId());

        assertThat(healed).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(1_000);
    }

    // ---- the health check ----------------------------------------------------------------------

    @Test
    void test_health_classifies_each_state() {
        ChatAttachment attachment = attachment("notes.pdf", 100);

        assertThat(chatDocumentIngestionService.health(state(attachment, 7, true)))
                .isEqualTo(ChatDocumentIngestionService.Health.DETACHED);
        assertThat(chatDocumentIngestionService.health(state(attachment, null, false)))
                .isEqualTo(ChatDocumentIngestionService.Health.UNINDEXED);

        when(ingestedDocumentService.findByChatAttachmentId(attachment.getId())).thenReturn(List.of());
        assertThat(chatDocumentIngestionService.health(state(attachment, 7, false)))
                .isEqualTo(ChatDocumentIngestionService.Health.BROKEN);
    }

    private ChatAttachmentIndexState state(ChatAttachment attachment, Integer chunkCount, boolean detached) {
        return new ChatAttachmentIndexState(attachment.getId(), userId, chatId, "application/pdf", 100,
                chunkCount, null, detached, null, null);
    }
}
