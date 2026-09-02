package com.solesonic.service.etl;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentEvent;
import com.solesonic.model.chat.attachment.ExtractionFailureReason;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.chat.events.NotificationEventMessage;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.service.rag.VectorStoreService;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.solesonic.model.rag.RetrievalMetadata.CHAT_ATTACHMENT_ID;
import static com.solesonic.model.rag.RetrievalMetadata.CHAT_ID;
import static com.solesonic.model.rag.RetrievalMetadata.FILE_NAME;
import static com.solesonic.model.rag.RetrievalMetadata.SCOPE;
import static com.solesonic.model.rag.RetrievalMetadata.USER_ID;
import static com.solesonic.model.chat.attachment.ExtractionFailureReason.DOCUMENT_TOO_LARGE;
import static com.solesonic.model.chat.attachment.ExtractionFailureReason.DOCUMENT_UNREADABLE;
import static com.solesonic.model.chat.attachment.ExtractionFailureReason.EMBEDDING_UNAVAILABLE;
import static com.solesonic.model.chat.attachment.ExtractionFailureReason.EXCEEDED_DOCUMENT_LIMIT;

/**
 * Indexes non-image attachments so the conversation can be asked about them.
 * <p>
 * This is the document counterpart of {@code ImageDescriptionService}, and it differs from it in one
 * structural way: a described image is small enough to hand the model in full, whereas a document is
 * not. So rather than rendering the content into the prompt, the text is split, embedded, and
 * written into the vector store at {@link RetrievalScope#CHAT} scope — the model reaches it through
 * the same retrieval it already uses for the global knowledge base, and only the passages that bear
 * on the question ever enter the context window.
 * <p>
 * Indexing happens once per attachment and is recorded as {@code chunkCount} on its row, so the cost
 * is paid on the turn the document is sent and never again.
 * <p>
 * Deliberately <em>not</em> routed through {@link EtlService} like file-upload ingestion is. That
 * pipeline runs a keyword enricher and a summary enricher, each of which makes an LLM call per
 * chunk; it is the right trade for a background job and completely wrong inline in a chat turn,
 * where it would add minutes before the first token. Splitting is all that happens here.
 * <p>
 * Nothing here may fail a chat turn. Every failure path leaves the turn intact and emits an
 * {@code attachment} SSE event saying the document was not indexed.
 */
@Service
public class ChatDocumentIngestionService {
    private static final Logger log = LoggerFactory.getLogger(ChatDocumentIngestionService.class);

    /**
     * A guard rather than a tuning knob, for the same reason the image limit is one: each document
     * costs an extraction plus an embedding pass per chunk, and the user is waiting.
     */
    static final int MAX_DOCUMENTS_PER_MESSAGE = 4;

    private final ChatAttachmentService chatAttachmentService;
    private final DocumentService documentService;
    private final EtlTextSplitter etlTextSplitter;
    private final VectorStoreService vectorStoreService;
    private final NotificationService notificationService;
    private final DataSize maxDocumentBytes;

    public ChatDocumentIngestionService(ChatAttachmentService chatAttachmentService,
                                        DocumentService documentService,
                                        EtlTextSplitter etlTextSplitter,
                                        VectorStoreService vectorStoreService,
                                        NotificationService notificationService,
                                        @Value("${solesonic.llm.attachment.document.max-size-bytes}") DataSize maxDocumentBytes) {
        this.chatAttachmentService = chatAttachmentService;
        this.documentService = documentService;
        this.etlTextSplitter = etlTextSplitter;
        this.vectorStoreService = vectorStoreService;
        this.notificationService = notificationService;
        this.maxDocumentBytes = maxDocumentBytes;
    }

    /**
     * The outcome of one ingestion attempt: exactly one of the two components is set.
     */
    private record IngestOutcome(Integer chunkCount, ExtractionFailureReason failureReason) {

        static IngestOutcome indexed(int chunkCount) {
            return new IngestOutcome(chunkCount, null);
        }

        static IngestOutcome skipped(ExtractionFailureReason failureReason) {
            return new IngestOutcome(null, failureReason);
        }
    }

    /**
     * Indexes every document named by one send, returning the file names that are now retrievable in
     * send order — or an empty list when the message carried no documents, or none could be read.
     * <p>
     * The names are what the caller tells the model about. The content deliberately is not: it
     * reaches the model only through retrieval, which is the whole point of indexing it.
     * <p>
     * Emits exactly one {@code attachment} event per document attachment before returning, whatever
     * happens in between — the frontend cannot name a failure it never hears about, so an id that
     * reaches no decision below is reported as unreadable rather than left silent.
     * <p>
     * {@code attachmentIds} is expected to hold only document attachments; {@code PromptService}
     * splits a send by content type so that each pass owns, and signals for, a disjoint set of ids.
     * The image filter below is a guard against that going wrong, not the split itself.
      * <p>
      * Blocking, and called from {@code PromptService} on a {@code boundedElastic} thread. Not
      * {@code @Transactional} on purpose: each repository call is its own short transaction, so no
      * pooled connection is held across a multi-second extraction.
     */
    public List<String> ingest(UUID chatId, UUID userId, Set<UUID> attachmentIds) {
        if (CollectionUtils.isEmpty(attachmentIds)) {
            return List.of();
        }

        Set<UUID> unsignalled = new LinkedHashSet<>(attachmentIds);

        try {
            return ingestAll(chatId, userId, attachmentIds, unsignalled);
        } finally {
            for (UUID attachmentId : Set.copyOf(unsignalled)) {
                log.warn("No extraction outcome was reached for attachment {} on chat {}", attachmentId, chatId);

                signal(chatId, unsignalled, attachmentId, IngestOutcome.skipped(DOCUMENT_UNREADABLE));
            }
        }
    }

    private List<String> ingestAll(UUID chatId,
                                   UUID userId,
                                   Set<UUID> attachmentIds,
                                   Set<UUID> unsignalled) {
        List<ChatAttachment> documents = chatAttachmentService.attachments(userId, attachmentIds).stream()
                .filter(attachment -> !ChatAttachmentService.isImage(attachment.getContentType()))
                .toList();

        if (documents.size() > MAX_DOCUMENTS_PER_MESSAGE) {
            log.warn("Indexing the first {} of {} document attachments on chat {}",
                    MAX_DOCUMENTS_PER_MESSAGE, documents.size(), chatId);

            for (ChatAttachment beyondLimit : documents.subList(MAX_DOCUMENTS_PER_MESSAGE, documents.size())) {
                chatAttachmentService.saveExtractionFailure(beyondLimit.getId(), EXCEEDED_DOCUMENT_LIMIT);

                signal(chatId, unsignalled, beyondLimit.getId(), IngestOutcome.skipped(EXCEEDED_DOCUMENT_LIMIT));
            }

            documents = documents.subList(0, MAX_DOCUMENTS_PER_MESSAGE);
        }

        List<String> indexed = new ArrayList<>(documents.size());

        for (ChatAttachment attachment : documents) {
            IngestOutcome ingestOutcome = ingestOne(chatId, userId, attachment);

            signal(chatId, unsignalled, attachment.getId(), ingestOutcome);

            if (ingestOutcome.chunkCount() == null) {
                continue;
            }

            indexed.add(attachment.getFileName());
        }

        return indexed;
    }

    /**
     * Emits the terminal event for one attachment, at most once per turn.
     */
    private void signal(UUID chatId, Set<UUID> unsignalled, UUID attachmentId, IngestOutcome ingestOutcome) {
        if (!unsignalled.remove(attachmentId)) {
            return;
        }

        ChatAttachmentEvent chatAttachmentEvent = (ingestOutcome.failureReason() == null)
                ? ChatAttachmentEvent.indexed(chatId, attachmentId, ingestOutcome.chunkCount())
                : ChatAttachmentEvent.notIndexed(chatId, attachmentId, ingestOutcome.failureReason());

        notificationService.emitAttachment(chatId, chatAttachmentEvent);
    }

    private IngestOutcome ingestOne(UUID chatId, UUID userId, ChatAttachment attachment) {
        if (attachment.getChunkCount() != null) {
            log.debug("Attachment {} is already indexed as {} chunk(s)",
                    attachment.getId(), attachment.getChunkCount());

            return IngestOutcome.indexed(attachment.getChunkCount());
        }

        if (attachment.getFileSizeBytes() > maxDocumentBytes.toBytes()) {
            log.warn("Attachment {} is {} bytes, above the {} document limit; leaving it unindexed",
                    attachment.getId(), attachment.getFileSizeBytes(), maxDocumentBytes);

            chatAttachmentService.saveExtractionFailure(attachment.getId(), DOCUMENT_TOO_LARGE);

            return IngestOutcome.skipped(DOCUMENT_TOO_LARGE);
        }

        notificationService.emitProgress(chatId, new NotificationEventMessage(
                attachment.getId().toString(),
                "Reading attached document " + attachment.getFileName(),
                null,
                null));

        List<Document> chunks;

        try {
            chunks = chunks(attachment);
        } catch (RuntimeException runtimeException) {
            log.warn("Could not extract text from attachment {}: {}",
                    attachment.getId(), runtimeException.getMessage());

            chatAttachmentService.saveExtractionFailure(attachment.getId(), DOCUMENT_UNREADABLE);

            return IngestOutcome.skipped(DOCUMENT_UNREADABLE);
        }

        if (chunks.isEmpty()) {
            log.warn("Attachment {} produced no readable text; leaving it unindexed", attachment.getId());

            chatAttachmentService.saveExtractionFailure(attachment.getId(), DOCUMENT_UNREADABLE);

            return IngestOutcome.skipped(DOCUMENT_UNREADABLE);
        }

        for (Document chunk : chunks) {
            scope(chunk, chatId, userId, attachment);
        }

        try {
            vectorStoreService.save(chunks);
        } catch (RuntimeException runtimeException) {
            log.warn("Could not embed attachment {}: {}", attachment.getId(), runtimeException.getMessage());

            chatAttachmentService.saveExtractionFailure(attachment.getId(), EMBEDDING_UNAVAILABLE);

            return IngestOutcome.skipped(EMBEDDING_UNAVAILABLE);
        }

        log.info("Indexed attachment {} as {} chunk(s) for chat {}", attachment.getId(), chunks.size(), chatId);

        chatAttachmentService.saveChunkCount(attachment.getId(), chunks.size());

        return IngestOutcome.indexed(chunks.size());
    }

    private List<Document> chunks(ChatAttachment attachment) {
        Resource resource = new ByteArrayResource(attachment.getFileData()) {
            @Override
            public String getFilename() {
                return attachment.getFileName();
            }
        };

        List<Document> documents = documentService.read(resource, attachment.getContentType());

        return etlTextSplitter.split(documents);
    }

    /**
     * Stamps the keys that confine this chunk to one conversation. Without them the chunk is
     * indistinguishable from shared ingested material and would answer other users' questions.
     * <p>
     * The ids go in as strings because a filter expression compares against a JSON string.
     */
    private static void scope(Document chunk, UUID chatId, UUID userId, ChatAttachment attachment) {
        Map<String, Object> metadata = chunk.getMetadata();

        metadata.put(SCOPE, RetrievalScope.CHAT.name());
        metadata.put(CHAT_ID, chatId.toString());
        metadata.put(USER_ID, userId.toString());
        metadata.put(CHAT_ATTACHMENT_ID, attachment.getId().toString());
        metadata.put(FILE_NAME, attachment.getFileName());
    }
}
