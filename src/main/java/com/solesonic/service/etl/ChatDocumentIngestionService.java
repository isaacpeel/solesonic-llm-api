package com.solesonic.service.etl;

import com.solesonic.exception.rag.DocumentReadException;
import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentEvent;
import com.solesonic.model.chat.attachment.ChatAttachmentIndexState;
import com.solesonic.model.chat.attachment.ExtractionFailureReason;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.chat.events.NotificationEventMessage;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.service.ingestion.IngestedDocumentService;
import com.solesonic.service.rag.VectorStoreService;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 * Each document gets an {@code ingested_document} row of its own —
 * {@link IngestedDocumentService#beginChatIngestion} — and the actual read/prepare/scope/embed work
 * is then delegated to {@link DocumentService#resourceToVectorStore(Resource, UUID)}, the same
 * status-tracked {@link EtlService#prepare(List, IngestedDocument)} pipeline file-upload and URI
 * ingestion use.
 * <p>
 * <strong>{@code chunkCount} is not trusted on its own.</strong> It once was, and {@code V3_27}
 * wiped every chunk and row while leaving the counts set, so those documents were never indexed
 * again. An attachment is now indexed when {@link #health} says it is not retrievable, which checks
 * for real chunks. Two more guarantees make re-indexing safe:
 * <ul>
 *   <li>a <em>lease</em> on the attachment row ({@code ChatAttachmentService.claimIndexing}), so the
 *       send path and the healer never index one attachment at the same time;</li>
 *   <li>a <em>pre-clean</em> under that lease, removing whatever an earlier attempt left behind, so
 *       a retry can never leave a second set of rows and chunks next to the first.</li>
 * </ul>
 * A <em>detached</em> attachment — promoted out of the conversation, or removed from it — is never
 * indexed again, whatever its rows look like.
 * <p>
 * Nothing here may fail a chat turn. Every failure path leaves the turn intact and, on the send path,
 * emits an {@code attachment} SSE event saying the document was not indexed. The heal path emits
 * nothing: the stream of the turn that triggered it may already be closed.
 */
@Service
public class ChatDocumentIngestionService {
    private static final Logger log = LoggerFactory.getLogger(ChatDocumentIngestionService.class);

    /**
     * A guard rather than a tuning knob, for the same reason the image limit is one: each document
     * costs an extraction plus an embedding pass per chunk, and the user is waiting.
     */
    static final int MAX_DOCUMENTS_PER_MESSAGE = 4;

    private static final Duration LEASE_POLL = Duration.ofMillis(500);

    /**
     * The statuses a row passes through while a worker is ingesting it.
     */
    private static final Set<DocumentStatus> INGESTING = EnumSet.of(
            DocumentStatus.IN_PROGRESS,
            DocumentStatus.PREPARING,
            DocumentStatus.TOKEN_SPLITTING,
            DocumentStatus.KEYWORD_ENRICHING,
            DocumentStatus.METADATA_ENRICHING);

    /**
     * Whether an attachment's document can be retrieved right now, and so whether to index it.
     */
    public enum Health {
        /**
         * Left the conversation on purpose (or ambiguous legacy). Treated as indexed; never touched.
         */
        DETACHED,
        /**
         * Never indexed, being indexed, or failed: {@code chunkCount} is null.
         */
        UNINDEXED,
        /**
         * Some row of it has chunks in the vector store.
         */
        HEALTHY,
        /**
         * No chunks, but a row is queued for refresh or being ingested by a live worker. Healthy for
         * now; indexing it as well would race that worker.
         */
        IN_FLIGHT,
        /**
         * Claims a chunk count but nothing retrievable is behind it.
         */
        BROKEN
    }

    private final ChatAttachmentService chatAttachmentService;
    private final DocumentService documentService;
    private final NotificationService notificationService;
    private final IngestedDocumentService ingestedDocumentService;
    private final VectorStoreService vectorStoreService;
    private final DataSize maxDocumentBytes;
    private final Duration staleLease;
    private final Duration leaseWait;
    private final Clock clock;

    @Autowired
    public ChatDocumentIngestionService(ChatAttachmentService chatAttachmentService,
                                        DocumentService documentService,
                                        NotificationService notificationService,
                                        IngestedDocumentService ingestedDocumentService,
                                        VectorStoreService vectorStoreService,
                                        @Value("${solesonic.llm.attachment.document.max-size-bytes}") DataSize maxDocumentBytes,
                                        @Value("${solesonic.llm.attachment.index.stale-lease:PT15M}") Duration staleLease,
                                        @Value("${solesonic.llm.attachment.index.lease-wait:PT30S}") Duration leaseWait) {
        this(chatAttachmentService, documentService, notificationService, ingestedDocumentService,
                vectorStoreService, maxDocumentBytes, staleLease, leaseWait, Clock.systemUTC());
    }

    ChatDocumentIngestionService(ChatAttachmentService chatAttachmentService,
                                 DocumentService documentService,
                                 NotificationService notificationService,
                                 IngestedDocumentService ingestedDocumentService,
                                 VectorStoreService vectorStoreService,
                                 DataSize maxDocumentBytes,
                                 Duration staleLease,
                                 Duration leaseWait,
                                 Clock clock) {
        this.chatAttachmentService = chatAttachmentService;
        this.documentService = documentService;
        this.notificationService = notificationService;
        this.ingestedDocumentService = ingestedDocumentService;
        this.vectorStoreService = vectorStoreService;
        this.maxDocumentBytes = maxDocumentBytes;
        this.staleLease = staleLease;
        this.leaseWait = leaseWait;
        this.clock = clock;
    }

    /**
     * The outcome of one ingestion attempt: exactly one of {@code chunkCount} and
     * {@code failureReason} is set.
     *
     * @param retrievable false for a detached attachment: its history still says indexed (so the
     *                    event does too), but it is not retrievable in this conversation, so the
     *                    model must not be told it is
     */
    private record IngestOutcome(Integer chunkCount, ExtractionFailureReason failureReason, boolean retrievable) {

        static IngestOutcome indexed(int chunkCount) {
            return new IngestOutcome(chunkCount, null, true);
        }

        static IngestOutcome detached(int chunkCount) {
            return new IngestOutcome(chunkCount, null, false);
        }

        static IngestOutcome skipped(ExtractionFailureReason failureReason) {
            return new IngestOutcome(null, failureReason, false);
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

    /**
     * Re-indexes one attachment of {@code chatId} in the background, if it needs it.
     * <p>
     * The same {@link #ingestOne} the send path uses, so the same health check, lease and pre-clean
     * apply. Emits no events. Refuses an attachment that is not bound to this chat and user, so a
     * caller cannot heal an attachment into a conversation it does not belong to.
     *
     * @return whether the attachment is retrievable afterwards
     */
    public boolean heal(UUID chatId, UUID userId, UUID attachmentId) {
        Optional<ChatAttachmentIndexState> indexState = chatAttachmentService.indexState(attachmentId);

        if (indexState.isEmpty()
                || !chatId.equals(indexState.get().chatId())
                || !userId.equals(indexState.get().userId())
                || ChatAttachmentService.isImage(indexState.get().contentType())) {
            log.warn("Refusing to heal attachment {}: not a document of chat {}", attachmentId, chatId);

            return false;
        }

        Optional<ChatAttachment> attachment = chatAttachmentService.forIndexing(attachmentId);

        if (attachment.isEmpty()) {
            return false;
        }

        // Never waits on a lease: the healer has two threads, and a leased attachment is being
        // indexed by someone else already.
        return ingestOne(chatId, userId, attachment.get(), false, System.nanoTime(), 0).retrievable();
    }

    /**
     * Whether an attachment's document can be retrieved right now.
     * <p>
     * Asks the vector store, not {@code chunkCount}: a count only says indexing once finished.
     * Chunk existence is checked with a containment query that uses the metadata GIN index.
     */
    public Health health(ChatAttachmentIndexState indexState) {
        if (indexState.indexDetached()) {
            return Health.DETACHED;
        }

        if (indexState.chunkCount() == null) {
            return Health.UNINDEXED;
        }

        boolean inFlight = false;

        for (IngestedDocument ingestedDocument : ingestedDocumentService.findByChatAttachmentId(indexState.id())) {
            if (vectorStoreService.hasChunks(ingestedDocument.getId())) {
                return Health.HEALTHY;
            }

            inFlight = inFlight || inFlight(ingestedDocument);
        }

        return inFlight ? Health.IN_FLIGHT : Health.BROKEN;
    }

    /**
     * Queued (a refresh: the poller will re-add the chunks), or being ingested by a worker that has
     * written progress within the stale cutoff. An ingesting row older than that belongs to a worker
     * that died, and is broken.
     */
    private boolean inFlight(IngestedDocument ingestedDocument) {
        DocumentStatus documentStatus = ingestedDocument.getDocumentStatus();

        if (documentStatus == DocumentStatus.QUEUED) {
            return true;
        }

        return INGESTING.contains(documentStatus)
                && ingestedDocument.getUpdated() != null
                && ingestedDocument.getUpdated().isAfter(now().minus(staleLease));
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

        // One wait budget for the whole send, not one per attachment, so a send carrying several
        // documents that are all leased elsewhere waits at most once.
        long waitDeadline = System.nanoTime() + leaseWait.toNanos();

        for (ChatAttachment attachment : documents) {
            IngestOutcome ingestOutcome = ingestOne(chatId, userId, attachment, true, waitDeadline, 1);

            signal(chatId, unsignalled, attachment.getId(), ingestOutcome);

            if (!ingestOutcome.retrievable()) {
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

    /**
     * Indexes one attachment if, and only if, it is not retrievable and not detached.
     *
     * @param attachment   carries the bytes; its lifecycle columns are re-read, not trusted
     * @param notify       whether to emit progress (the send path) or stay silent (the heal path)
     * @param waitDeadline {@link System#nanoTime()} after which a held lease is no longer waited out;
     *                     shared by every attachment of one send
     * @param retries      how many more times to re-decide after a lost claim (bounds the recursion)
     */
    private IngestOutcome ingestOne(UUID chatId,
                                    UUID userId,
                                    ChatAttachment attachment,
                                    boolean notify,
                                    long waitDeadline,
                                    int retries) {
        UUID attachmentId = attachment.getId();

        Optional<ChatAttachmentIndexState> current = chatAttachmentService.indexState(attachmentId);

        if (current.isEmpty()) {
            log.warn("Attachment {} disappeared before it could be indexed", attachmentId);

            return IngestOutcome.skipped(DOCUMENT_UNREADABLE);
        }

        ChatAttachmentIndexState indexState = current.get();

        switch (health(indexState)) {
            case DETACHED -> {
                log.debug("Attachment {} has left its conversation; not indexing it", attachmentId);

                return IngestOutcome.detached(indexState.chunkCount() == null ? 0 : indexState.chunkCount());
            }
            case HEALTHY, IN_FLIGHT -> {
                log.debug("Attachment {} is already indexed as {} chunk(s)", attachmentId, indexState.chunkCount());

                return IngestOutcome.indexed(indexState.chunkCount());
            }
            case BROKEN -> log.warn("Attachment {} claims {} chunk(s) but none are retrievable; re-indexing",
                    attachmentId, indexState.chunkCount());
            case UNINDEXED -> {
                // indexed below
            }
        }

        if (indexState.fileSizeBytes() > maxDocumentBytes.toBytes()) {
            log.warn("Attachment {} is {} bytes, above the {} document limit; leaving it unindexed",
                    attachmentId, indexState.fileSizeBytes(), maxDocumentBytes);

            chatAttachmentService.saveExtractionFailure(attachmentId, DOCUMENT_TOO_LARGE);

            return IngestOutcome.skipped(DOCUMENT_TOO_LARGE);
        }

        // The lease token. Truncated to the column's precision so that release and the final
        // writes, which match on it, find the value the database actually stored.
        ZonedDateTime claimedAt = now().truncatedTo(ChronoUnit.MICROS);

        // Conditional on the attempt this decision was based on: if another worker claimed since,
        // the claim fails and the decision is made again from fresh state rather than redoing
        // (and first pre-cleaning) what that worker may just have finished.
        if (!chatAttachmentService.claimIndexing(attachmentId, claimedAt, claimedAt.minus(staleLease),
                indexState.indexAttemptedAt())) {
            if (retries > 0 && awaitLease(attachmentId, waitDeadline)) {
                return ingestOne(chatId, userId, attachment, notify, waitDeadline, retries - 1);
            }

            log.info("Attachment {} is being indexed by another worker; going ahead without it", attachmentId);

            return IngestOutcome.skipped(EMBEDDING_UNAVAILABLE);
        }

        try {
            return ingestClaimed(chatId, userId, attachment, notify, claimedAt);
        } finally {
            try {
                chatAttachmentService.releaseIndexing(attachmentId, claimedAt);
            } catch (RuntimeException runtimeException) {
                // The lease goes stale on its own; a failed release only delays the next attempt.
                log.warn("Could not release the indexing lease on attachment {}: {}",
                        attachmentId, runtimeException.getMessage());
            }
        }
    }

    /**
     * The indexing itself, under a held lease.
     * <p>
     * A worker that outlives the stale cutoff can have its lease taken over. Its final write is then
     * refused (it is conditional on the lease), and the new holder's result stands. What it cannot
     * undo is chunks it embedded after the new holder's pre-clean; the stale cutoff is set far above
     * any real ingest time so that this stays theoretical.
     */
    private IngestOutcome ingestClaimed(UUID chatId,
                                        UUID userId,
                                        ChatAttachment attachment,
                                        boolean notify,
                                        ZonedDateTime claimedAt) {
        UUID attachmentId = attachment.getId();

        int chunkCount;

        try {
            // Whatever an earlier attempt left behind: a row from a crashed ingest, or chunks whose
            // count never got recorded. Under the lease, so no other worker is writing them.
            ingestedDocumentService.deleteByChatAttachmentId(attachmentId);
            vectorStoreService.deleteByChatAttachmentId(attachmentId);

            if (notify) {
                notificationService.emitProgress(chatId, new NotificationEventMessage(
                        attachmentId.toString(),
                        "Reading attached document " + attachment.getFileName(),
                        null,
                        null));
            }

            Resource resource = new ByteArrayResource(attachment.getFileData()) {
                @Override
                public String getFilename() {
                    return attachment.getFileName();
                }
            };

            IngestedDocument ingestedDocument = ingestedDocumentService.beginChatIngestion(
                    chatId, userId, attachmentId, attachment.getFileName(), attachment.getContentType());

            chunkCount = documentService.resourceToVectorStore(resource, ingestedDocument.getId());

            Optional<ChatAttachmentIndexState> after = chatAttachmentService.indexState(attachmentId);

            if (after.isEmpty() || after.get().indexDetached()) {
                // Deleted, or removed from the conversation, while it was being embedded. Whoever did
                // that already swept the rows and chunks that existed then; what was written since
                // has nobody left to remove it.
                log.info("Attachment {} was {} while it was being indexed; discarding its chunks", attachmentId,
                        after.isEmpty() ? "deleted" : "detached");

                ingestedDocumentService.deleteByChatAttachmentId(attachmentId);
                vectorStoreService.deleteByChatAttachmentId(attachmentId);

                return after.isEmpty()
                        ? IngestOutcome.skipped(DOCUMENT_UNREADABLE)
                        : IngestOutcome.detached(after.get().chunkCount() == null ? 0 : after.get().chunkCount());
            }

            if (!chatAttachmentService.saveChunkCountUnderLease(attachmentId, chunkCount, claimedAt)) {
                log.warn("Lost the indexing lease on attachment {} before finishing; leaving it to the new holder",
                        attachmentId);

                return IngestOutcome.skipped(EMBEDDING_UNAVAILABLE);
            }
        } catch (DocumentReadException documentReadException) {
            log.warn("Could not extract text from attachment {}: {}",
                    attachmentId, documentReadException.getMessage());

            recordFailure(attachmentId, DOCUMENT_UNREADABLE, claimedAt);

            return IngestOutcome.skipped(DOCUMENT_UNREADABLE);
        } catch (RuntimeException runtimeException) {
            log.warn("Could not embed attachment {}: {}", attachmentId, runtimeException.getMessage());

            // The pre-clean has already run, so a document that was BROKEN is now UNINDEXED with a
            // transient reason: the healer retries it after the cooldown.
            recordFailure(attachmentId, EMBEDDING_UNAVAILABLE, claimedAt);

            return IngestOutcome.skipped(EMBEDDING_UNAVAILABLE);
        }

        log.info("Indexed attachment {} as {} chunk(s) for chat {}", attachmentId, chunkCount, chatId);

        return IngestOutcome.indexed(chunkCount);
    }

    /**
     * Waits for another worker's lease to clear, polling the row, until {@code deadline}.
     *
     * @return true when it cleared (the caller re-reads and decides again), false on timeout, on
     *         interruption, or when the attachment is gone
     */
    private boolean awaitLease(UUID attachmentId, long deadline) {
        while (true) {
            Optional<ChatAttachmentIndexState> indexState = chatAttachmentService.indexState(attachmentId);

            if (indexState.isEmpty()) {
                return false;
            }

            if (!indexState.get().leased(now().minus(staleLease))) {
                return true;
            }

            long remainingNanos = deadline - System.nanoTime();

            if (remainingNanos <= 0) {
                return false;
            }

            try {
                Thread.sleep(Math.max(1, Math.min(LEASE_POLL.toMillis(), Duration.ofNanos(remainingNanos).toMillis())));
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();

                return false;
            }
        }
    }

    private void recordFailure(UUID attachmentId, ExtractionFailureReason extractionFailureReason, ZonedDateTime claimedAt) {
        try {
            chatAttachmentService.saveExtractionFailureUnderLease(attachmentId, extractionFailureReason, claimedAt);
        } catch (RuntimeException runtimeException) {
            log.warn("Could not record why attachment {} was not indexed: {}",
                    attachmentId, runtimeException.getMessage());
        }
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }
}
