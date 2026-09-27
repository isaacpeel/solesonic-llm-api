package com.solesonic.service.etl;

import com.solesonic.exception.rag.DocumentReadException;
import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentEvent;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.service.ingestion.IngestedDocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

@ExtendWith(MockitoExtension.class)
class ChatDocumentIngestionServiceTest {

    @Mock
    private ChatAttachmentService chatAttachmentService;

    @Mock
    private DocumentService documentService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private IngestedDocumentService ingestedDocumentService;

    private ChatDocumentIngestionService chatDocumentIngestionService;

    @BeforeEach
    void beforeEach() {
        chatDocumentIngestionService = new ChatDocumentIngestionService(
                chatAttachmentService,
                documentService,
                notificationService,
                ingestedDocumentService,
                DataSize.ofMegabytes(10));
    }

    private static ChatAttachment attachment(UUID attachmentId, String fileName, long fileSizeBytes) {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(attachmentId);
        attachment.setFileName(fileName);
        attachment.setContentType("application/pdf");
        attachment.setFileData("hello".getBytes());
        attachment.setFileSizeBytes(fileSizeBytes);

        return attachment;
    }

    private static IngestedDocument ingestedDocument(UUID ingestedDocumentId) {
        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(ingestedDocumentId);

        return ingestedDocument;
    }

    /**
     * The happy path opens the tracked row and then delegates the whole read/prepare/scope/embed
     * pipeline to {@code DocumentService}, which is where the scope metadata is now stamped from.
     */
    @Test
    void test_ingest_opens_a_tracked_row_and_delegates_to_document_service() {
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID ingestedDocumentId = UUID.randomUUID();

        ChatAttachment attachment = attachment(attachmentId, "notes.pdf", 100);
        when(chatAttachmentService.attachments(userId, Set.of(attachmentId))).thenReturn(List.of(attachment));
        when(ingestedDocumentService.beginChatIngestion(chatId, userId, attachmentId, "notes.pdf", "application/pdf"))
                .thenReturn(ingestedDocument(ingestedDocumentId));
        when(documentService.resourceToVectorStore(any(Resource.class), eq(ingestedDocumentId)))
                .thenReturn(3);

        List<String> indexed = chatDocumentIngestionService.ingest(chatId, userId, Set.of(attachmentId));

        assertThat(indexed).containsExactly("notes.pdf");

        verify(ingestedDocumentService)
                .beginChatIngestion(chatId, userId, attachmentId, "notes.pdf", "application/pdf");
        verify(documentService).resourceToVectorStore(any(Resource.class), eq(ingestedDocumentId));
        verify(chatAttachmentService).saveChunkCount(attachmentId, 3);
        verify(notificationService).emitAttachment(chatId, ChatAttachmentEvent.indexed(chatId, attachmentId, 3));
    }

    /**
     * The failure contract this class exposes is unchanged by the routing change. Marking the row
     * {@code FAILED} belongs to {@code DocumentService}, and is asserted in {@code DocumentServiceTest}.
     */
    @Test
    void test_ingest_maps_document_read_exception_to_document_unreadable() {
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID ingestedDocumentId = UUID.randomUUID();

        ChatAttachment attachment = attachment(attachmentId, "notes.pdf", 100);
        when(chatAttachmentService.attachments(userId, Set.of(attachmentId))).thenReturn(List.of(attachment));
        when(ingestedDocumentService.beginChatIngestion(chatId, userId, attachmentId, "notes.pdf", "application/pdf"))
                .thenReturn(ingestedDocument(ingestedDocumentId));
        when(documentService.resourceToVectorStore(any(Resource.class), eq(ingestedDocumentId)))
                .thenThrow(new DocumentReadException("no readable text"));

        List<String> indexed = chatDocumentIngestionService.ingest(chatId, userId, Set.of(attachmentId));

        assertThat(indexed).isEmpty();

        verify(chatAttachmentService).saveExtractionFailure(attachmentId, DOCUMENT_UNREADABLE);
        verify(notificationService).emitAttachment(chatId, ChatAttachmentEvent.notIndexed(chatId, attachmentId, DOCUMENT_UNREADABLE));
        verify(chatAttachmentService, never()).saveChunkCount(any(), anyInt());
    }

    @Test
    void test_ingest_maps_other_runtime_exception_to_embedding_unavailable() {
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID ingestedDocumentId = UUID.randomUUID();

        ChatAttachment attachment = attachment(attachmentId, "notes.pdf", 100);
        when(chatAttachmentService.attachments(userId, Set.of(attachmentId))).thenReturn(List.of(attachment));
        when(ingestedDocumentService.beginChatIngestion(chatId, userId, attachmentId, "notes.pdf", "application/pdf"))
                .thenReturn(ingestedDocument(ingestedDocumentId));
        when(documentService.resourceToVectorStore(any(Resource.class), eq(ingestedDocumentId)))
                .thenThrow(new RuntimeException("vector store unavailable"));

        List<String> indexed = chatDocumentIngestionService.ingest(chatId, userId, Set.of(attachmentId));

        assertThat(indexed).isEmpty();

        verify(chatAttachmentService).saveExtractionFailure(attachmentId, EMBEDDING_UNAVAILABLE);
        verify(notificationService).emitAttachment(chatId, ChatAttachmentEvent.notIndexed(chatId, attachmentId, EMBEDDING_UNAVAILABLE));
    }

    /**
     * This guard is orchestration that stayed in this class after the refactor; it must never reach
     * {@code DocumentService} at all — and, now that a tracked row exists, must not open one either,
     * or every oversized attachment would leave a row nothing ever completes.
     */
    @Test
    void test_ingest_rejects_oversized_attachment_without_opening_a_row() {
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();

        ChatAttachment attachment = attachment(attachmentId, "huge.pdf", DataSize.ofMegabytes(20).toBytes());
        when(chatAttachmentService.attachments(userId, Set.of(attachmentId))).thenReturn(List.of(attachment));

        List<String> indexed = chatDocumentIngestionService.ingest(chatId, userId, Set.of(attachmentId));

        assertThat(indexed).isEmpty();

        verify(chatAttachmentService).saveExtractionFailure(attachmentId, DOCUMENT_TOO_LARGE);
        verifyNoInteractions(documentService);
        verifyNoInteractions(ingestedDocumentService);
    }

    /**
     * An attachment already indexed on a prior turn is reported indexed again, but is never
     * re-embedded — and opens no second row for the one its first turn already completed.
     */
    @Test
    void test_ingest_short_circuits_an_already_indexed_attachment() {
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();

        ChatAttachment attachment = attachment(attachmentId, "notes.pdf", 100);
        attachment.setChunkCount(7);
        when(chatAttachmentService.attachments(userId, Set.of(attachmentId))).thenReturn(List.of(attachment));

        List<String> indexed = chatDocumentIngestionService.ingest(chatId, userId, Set.of(attachmentId));

        assertThat(indexed).containsExactly("notes.pdf");

        verifyNoInteractions(documentService);
        verifyNoInteractions(ingestedDocumentService);
        verify(chatAttachmentService, never()).saveChunkCount(any(), anyInt());
        verify(notificationService).emitAttachment(chatId, ChatAttachmentEvent.indexed(chatId, attachmentId, 7));
    }

    /**
     * Attachments beyond {@code MAX_DOCUMENTS_PER_MESSAGE} are turned away before extraction starts,
     * so they too leave no row behind for the lifecycle cleanup to find.
     */
    @Test
    void test_ingest_rejects_attachments_beyond_the_per_message_limit_without_opening_a_row() {
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        List<ChatAttachment> attachments = new ArrayList<>();
        Set<UUID> attachmentIds = new LinkedHashSet<>();

        for (int index = 0; index <= ChatDocumentIngestionService.MAX_DOCUMENTS_PER_MESSAGE; index++) {
            ChatAttachment attachment = attachment(UUID.randomUUID(), "notes-" + index + ".pdf", 100);
            attachment.setChunkCount(1);

            attachments.add(attachment);
            attachmentIds.add(attachment.getId());
        }

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(attachments);

        chatDocumentIngestionService.ingest(chatId, userId, attachmentIds);

        ChatAttachment beyondLimit = attachments.get(ChatDocumentIngestionService.MAX_DOCUMENTS_PER_MESSAGE);

        verify(chatAttachmentService).saveExtractionFailure(beyondLimit.getId(), EXCEEDED_DOCUMENT_LIMIT);
        verifyNoInteractions(documentService);
        verifyNoInteractions(ingestedDocumentService);
    }
}
