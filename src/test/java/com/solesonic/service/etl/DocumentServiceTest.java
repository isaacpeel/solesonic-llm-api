package com.solesonic.service.etl;

import com.solesonic.exception.rag.DocumentReadException;
import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.repository.chat.ChatAttachmentRepository;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.service.ingestion.IngestedDocumentService;
import com.solesonic.service.rag.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.model.rag.RetrievalMetadata.CHAT_ATTACHMENT_ID;
import static com.solesonic.model.rag.RetrievalMetadata.CHAT_ID;
import static com.solesonic.model.rag.RetrievalMetadata.FILE_NAME;
import static com.solesonic.model.rag.RetrievalMetadata.SCOPE;
import static com.solesonic.model.rag.RetrievalMetadata.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final String CHAT_FILE_NAME = "notes.txt";

    @Mock
    private VectorStoreService vectorStoreService;

    @Mock
    private EtlService etlService;

    @Mock
    private IngestedDocumentService ingestedDocumentService;

    @Mock
    private UriContentFetcher uriContentFetcher;

    @Mock
    private ChatAttachmentRepository chatAttachmentRepository;

    private DocumentService documentService;

    @BeforeEach
    void beforeEach() {
        documentService = spy(new DocumentService(vectorStoreService, etlService, ingestedDocumentService,
                uriContentFetcher, chatAttachmentRepository));
        doReturn(List.of(new Document("raw text"))).when(documentService).read(any(Resource.class), anyString());
    }

    private static Resource resource() {
        return new ByteArrayResource("hello".getBytes()) {
            @Override
            public String getFilename() {
                return CHAT_FILE_NAME;
            }
        };
    }

    /**
     * The row a chat attachment is ingested against: {@code CHAT} scope, an owner, the two entity
     * metadata keys {@code IngestedDocumentService.beginChatIngestion} writes, and — deliberately —
     * no file bytes of its own.
     */
    private static IngestedDocument chatIngestedDocument(UUID ingestedDocumentId,
                                                         UUID chatId,
                                                         UUID userId,
                                                         UUID chatAttachmentId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(IngestedDocument.ORIGINAL_FILE_NAME, CHAT_FILE_NAME);
        metadata.put(IngestedDocument.CHAT_ID, chatId.toString());
        metadata.put(IngestedDocument.CHAT_ATTACHMENT_ID, chatAttachmentId.toString());

        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(ingestedDocumentId);
        ingestedDocument.setDocumentStatus(DocumentStatus.IN_PROGRESS);
        ingestedDocument.setDocumentSource(DocumentSource.CHAT);
        ingestedDocument.setScope(RetrievalScope.CHAT);
        ingestedDocument.setUserId(userId);
        ingestedDocument.setContentType(MediaType.TEXT_PLAIN_VALUE);
        ingestedDocument.setFileData(new byte[0]);
        ingestedDocument.setFileName(CHAT_FILE_NAME);
        ingestedDocument.setMetadata(metadata);
        ingestedDocument.setCreated(ZonedDateTime.now());
        ingestedDocument.setUpdated(ZonedDateTime.now());

        return ingestedDocument;
    }

    /**
     * A chat attachment now runs the same status-tracked pipeline the queued path does, and its
     * chunks carry {@code INGESTED_DOCUMENT_ID} like every other scope's do — the four chat keys are
     * read off the row rather than handed in by the caller.
     */
    @Test
    void test_resourceToVectorStore_resource_and_id_stamps_chat_metadata_and_returns_chunk_count() {
        UUID ingestedDocumentId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID chatAttachmentId = UUID.randomUUID();

        IngestedDocument ingestedDocument =
                chatIngestedDocument(ingestedDocumentId, chatId, userId, chatAttachmentId);
        when(ingestedDocumentService.get(ingestedDocumentId)).thenReturn(ingestedDocument);

        Document chunkOne = new Document("chunk one");
        Document chunkTwo = new Document("chunk two");
        when(etlService.prepare(anyList(), eq(ingestedDocument))).thenReturn(List.of(chunkOne, chunkTwo));

        int chunkCount = documentService.resourceToVectorStore(resource(), ingestedDocumentId);

        assertThat(chunkCount).isEqualTo(2);

        for (Document chunk : List.of(chunkOne, chunkTwo)) {
            assertThat(chunk.getMetadata())
                    .containsEntry(SCOPE, RetrievalScope.CHAT.name())
                    .containsEntry(DocumentService.INGESTED_DOCUMENT_ID, ingestedDocumentId)
                    .containsEntry(USER_ID, userId.toString())
                    .containsEntry(CHAT_ID, chatId.toString())
                    .containsEntry(CHAT_ATTACHMENT_ID, chatAttachmentId.toString())
                    .containsEntry(FILE_NAME, CHAT_FILE_NAME);
        }

        verify(etlService).prepare(anyList(), eq(ingestedDocument));
        verify(etlService, never()).prepare(anyList());
        verify(vectorStoreService).save(List.of(chunkOne, chunkTwo));
        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.COMPLETED);
    }

    /**
     * The bytes come from the parameter, never from the row. A chat row's {@code fileData} is empty
     * on purpose — the attachment already holds the only copy — so reading the row instead would
     * extract nothing at all.
     */
    @Test
    void test_resourceToVectorStore_resource_and_id_reads_the_passed_resource_not_the_empty_row_bytes() {
        UUID ingestedDocumentId = UUID.randomUUID();

        IngestedDocument ingestedDocument = chatIngestedDocument(
                ingestedDocumentId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(ingestedDocumentService.get(ingestedDocumentId)).thenReturn(ingestedDocument);
        when(etlService.prepare(anyList(), eq(ingestedDocument))).thenReturn(List.of(new Document("chunk")));

        Resource resource = resource();

        documentService.resourceToVectorStore(resource, ingestedDocumentId);

        ArgumentCaptor<Resource> resourceCaptor = ArgumentCaptor.forClass(Resource.class);
        verify(documentService).read(resourceCaptor.capture(), eq(MediaType.TEXT_PLAIN_VALUE));

        assertThat(resourceCaptor.getValue()).isSameAs(resource);
    }

    /**
     * The caller's {@code DOCUMENT_UNREADABLE} branch depends on this exception type surviving, and
     * the row may not be left on {@code IN_PROGRESS} forever now that one exists.
     */
    @Test
    void test_resourceToVectorStore_resource_and_id_marks_failed_and_throws_when_no_chunks_produced() {
        UUID ingestedDocumentId = UUID.randomUUID();

        IngestedDocument ingestedDocument = chatIngestedDocument(
                ingestedDocumentId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(ingestedDocumentService.get(ingestedDocumentId)).thenReturn(ingestedDocument);
        when(etlService.prepare(anyList(), eq(ingestedDocument))).thenReturn(List.of());

        assertThatThrownBy(() -> documentService.resourceToVectorStore(resource(), ingestedDocumentId))
                .isInstanceOf(DocumentReadException.class);

        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.FAILED);
        verify(ingestedDocumentService, never()).update(ingestedDocument, DocumentStatus.COMPLETED);
        verifyNoInteractions(vectorStoreService);
    }

    /**
     * A chat chunk is deleted by its {@code chatAttachmentId}. Stamping a null there would write a
     * chunk that {@code VectorStoreService.deleteByChatAttachmentId} can never match, so a document
     * the user removed would keep answering questions — the ingestion has to fail instead.
     */
    @Test
    void test_resourceToVectorStore_resource_and_id_refuses_a_chat_row_missing_its_chat_ids() {
        UUID ingestedDocumentId = UUID.randomUUID();

        IngestedDocument ingestedDocument = chatIngestedDocument(
                ingestedDocumentId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ingestedDocument.setMetadata(new HashMap<>());

        when(ingestedDocumentService.get(ingestedDocumentId)).thenReturn(ingestedDocument);
        when(etlService.prepare(anyList(), eq(ingestedDocument))).thenReturn(List.of(new Document("chunk")));

        assertThatThrownBy(() -> documentService.resourceToVectorStore(resource(), ingestedDocumentId))
                .isInstanceOf(IllegalStateException.class);

        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.FAILED);
        verifyNoInteractions(vectorStoreService);
    }

    /**
     * Anything else is the caller's {@code EMBEDDING_UNAVAILABLE} branch, and reaches it unwrapped.
     */
    @Test
    void test_resourceToVectorStore_resource_and_id_marks_failed_and_rethrows_a_runtime_exception() {
        UUID ingestedDocumentId = UUID.randomUUID();

        IngestedDocument ingestedDocument = chatIngestedDocument(
                ingestedDocumentId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(ingestedDocumentService.get(ingestedDocumentId)).thenReturn(ingestedDocument);

        RuntimeException embeddingFailure = new RuntimeException("vector store unavailable");
        when(etlService.prepare(anyList(), eq(ingestedDocument))).thenThrow(embeddingFailure);

        assertThatThrownBy(() -> documentService.resourceToVectorStore(resource(), ingestedDocumentId))
                .isSameAs(embeddingFailure);

        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.FAILED);
        verifyNoInteractions(vectorStoreService);
    }

    /**
     * The queued path is where a refreshed chat attachment comes back through, and the row it finds
     * has no bytes of its own — beginChatIngestion leaves fileData empty because the attachment
     * holds the only copy. Reading the row as-is would extract nothing and mark the document
     * COMPLETED with zero chunks, which is the same shape as a document that silently stopped
     * working.
     */
    @Test
    void test_resourceToVectorStore_uuid_rereads_an_attachment_sourced_chat_row_from_its_attachment() {
        UUID ingestedDocumentId = UUID.randomUUID();
        UUID chatAttachmentId = UUID.randomUUID();

        IngestedDocument ingestedDocument = chatIngestedDocument(
                ingestedDocumentId, UUID.randomUUID(), UUID.randomUUID(), chatAttachmentId);

        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setId(chatAttachmentId);
        chatAttachment.setFileData("attached text".getBytes());
        chatAttachment.setContentType(MediaType.TEXT_PLAIN_VALUE);

        when(ingestedDocumentService.get(ingestedDocumentId)).thenReturn(ingestedDocument);
        when(chatAttachmentRepository.findById(chatAttachmentId)).thenReturn(Optional.of(chatAttachment));
        when(etlService.prepare(anyList(), eq(ingestedDocument))).thenReturn(List.of(new Document("chunk")));

        documentService.resourceToVectorStore(ingestedDocumentId);

        assertThat(ingestedDocument.getFileData()).isEqualTo("attached text".getBytes());
        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.COMPLETED);
    }

    /**
     * A document uploaded straight to a conversation has no attachment and never will, so requiring
     * one would make the CHAT collection's own uploads unembeddable. What it does still require is
     * the chat id, which is what the CHAT retrieval tier filters on.
     */
    @Test
    void test_resourceToVectorStore_uuid_embeds_a_chat_document_that_came_from_no_attachment() {
        UUID ingestedDocumentId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(IngestedDocument.CHAT_ID, chatId.toString());

        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(ingestedDocumentId);
        ingestedDocument.setDocumentSource(DocumentSource.USER);
        ingestedDocument.setScope(RetrievalScope.CHAT);
        ingestedDocument.setChatId(chatId);
        ingestedDocument.setUserId(userId);
        ingestedDocument.setContentType(MediaType.TEXT_PLAIN_VALUE);
        ingestedDocument.setFileData("hello".getBytes());
        ingestedDocument.setFileName(CHAT_FILE_NAME);
        ingestedDocument.setMetadata(metadata);

        when(ingestedDocumentService.get(ingestedDocumentId)).thenReturn(ingestedDocument);

        Document chunk = new Document("chunk");
        when(etlService.prepare(anyList(), eq(ingestedDocument))).thenReturn(List.of(chunk));

        documentService.resourceToVectorStore(ingestedDocumentId);

        assertThat(chunk.getMetadata())
                .containsEntry(SCOPE, RetrievalScope.CHAT.name())
                .containsEntry(CHAT_ID, chatId.toString())
                .containsEntry(USER_ID, userId.toString())
                .containsEntry(FILE_NAME, CHAT_FILE_NAME)
                .doesNotContainKey(CHAT_ATTACHMENT_ID);

        verifyNoInteractions(chatAttachmentRepository);
        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.COMPLETED);
    }

    /**
     * The queued path still runs the status-tracked overload of {@code EtlService.prepare} — the
     * same three processing calls as the untracked one, with {@code IngestedDocument} status
     * transitions interleaved.
     */
    @Test
    void test_resourceToVectorStore_uuid_runs_status_tracked_prepare_and_stamps_ingested_document_id() {
        UUID ingestedDocumentId = UUID.randomUUID();

        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(ingestedDocumentId);
        ingestedDocument.setDocumentSource(DocumentSource.USER);
        ingestedDocument.setScope(RetrievalScope.GLOBAL);
        ingestedDocument.setContentType(MediaType.TEXT_PLAIN_VALUE);
        ingestedDocument.setFileData("hello".getBytes());
        ingestedDocument.setFileName("shared.txt");
        ingestedDocument.setCreated(ZonedDateTime.now());
        ingestedDocument.setUpdated(ZonedDateTime.now());

        when(ingestedDocumentService.get(ingestedDocumentId)).thenReturn(ingestedDocument);

        Document chunk = new Document("chunk");
        when(etlService.prepare(anyList(), eq(ingestedDocument))).thenReturn(List.of(chunk));

        documentService.resourceToVectorStore(ingestedDocumentId);

        assertThat(chunk.getMetadata())
                .containsEntry(SCOPE, RetrievalScope.GLOBAL.name())
                .containsEntry(DocumentService.INGESTED_DOCUMENT_ID, ingestedDocumentId)
                .doesNotContainKey(USER_ID);

        verify(etlService).prepare(anyList(), eq(ingestedDocument));
        verify(etlService, never()).prepare(anyList());
        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.COMPLETED);
    }

    @Test
    void test_resourceToVectorStore_uuid_user_scope_also_stamps_user_id() {
        UUID ingestedDocumentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(ingestedDocumentId);
        ingestedDocument.setDocumentSource(DocumentSource.USER);
        ingestedDocument.setScope(RetrievalScope.USER);
        ingestedDocument.setUserId(ownerId);
        ingestedDocument.setContentType(MediaType.TEXT_PLAIN_VALUE);
        ingestedDocument.setFileData("hello".getBytes());
        ingestedDocument.setFileName("mine.txt");
        ingestedDocument.setCreated(ZonedDateTime.now());
        ingestedDocument.setUpdated(ZonedDateTime.now());

        when(ingestedDocumentService.get(ingestedDocumentId)).thenReturn(ingestedDocument);

        Document chunk = new Document("chunk");
        when(etlService.prepare(anyList(), eq(ingestedDocument))).thenReturn(List.of(chunk));

        documentService.resourceToVectorStore(ingestedDocumentId);

        assertThat(chunk.getMetadata())
                .containsEntry(SCOPE, RetrievalScope.USER.name())
                .containsEntry(USER_ID, ownerId.toString());
    }
}
