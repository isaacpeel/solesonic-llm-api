package com.solesonic.service.ingestion;

import com.solesonic.exception.ChatException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.model.ingestion.IngestedDocument.FILE_SIZE_BYTES;

/**
 * Ingested documents, in two scope-specific method families rather than one family that resolves
 * scope at runtime.
 * <p>
 * The split is deliberate and follows {@code ChatAttachmentService}, which has no "which scope is
 * this" branching at all because a chat attachment only ever means one scope. Here the scope is
 * decided by which collection the request arrived through, so each method knows its own answer
 * before it starts; what differs between a pair is only the scoped fetch it opens with, and the
 * ownership in that fetch is a {@code where} clause rather than a comparison the caller could skip.
 */
@Service
public class IngestedDocumentService {
    private static final Logger log = LoggerFactory.getLogger(IngestedDocumentService.class);

    private static final String NOT_FOUND_REASON = "No such ingested document";

    private final IngestedDocumentRepository ingestedDocumentRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final VectorStoreService vectorStoreService;

    public IngestedDocumentService(IngestedDocumentRepository ingestedDocumentRepository,
                                   StatusHistoryRepository statusHistoryRepository,
                                   VectorStoreService vectorStoreService) {
        this.ingestedDocumentRepository = ingestedDocumentRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.vectorStoreService = vectorStoreService;
    }

    public Page<IngestedDocumentSummary> listGlobal(Pageable pageable) {
        log.debug("Listing global ingested documents page {}", pageable.getPageNumber());

        return summaries(ingestedDocumentRepository.findAllGlobal(pageable));
    }

    public Page<IngestedDocumentSummary> listForUser(UUID userId, Pageable pageable) {
        log.debug("Listing ingested documents for user {} page {}", userId, pageable.getPageNumber());

        return summaries(ingestedDocumentRepository.findAllByUserId(userId, pageable));
    }

    public Page<IngestedDocumentSummary> listForChat(UUID chatId, Pageable pageable) {
        log.debug("Listing ingested documents for chat {} page {}", chatId, pageable.getPageNumber());

        return summaries(ingestedDocumentRepository.findAllByChatId(chatId, pageable));
    }

    public IngestedDocumentSummary getGlobal(UUID documentId) {
        return summary(globalDocument(documentId));
    }

    public IngestedDocumentSummary getForUser(UUID documentId, UUID userId) {
        return summary(userDocument(documentId, userId));
    }

    public IngestedDocumentSummary getForChat(UUID documentId, UUID chatId) {
        return summary(chatDocument(documentId, chatId));
    }

    @Transactional
    public void deleteGlobal(UUID documentId) {
        delete(globalDocument(documentId));
    }

    @Transactional
    public void deleteForUser(UUID documentId, UUID userId) {
        delete(userDocument(documentId, userId));
    }

    /**
     * Removes one document from a conversation, its chunks and status history with it.
     * <p>
     * The {@code chat_attachment} row a document arrived on, when it arrived on one, is deliberately
     * left alone: the message that carried it is part of the conversation's history, and deleting the
     * attachment would rewrite that history rather than stop the document being retrieved. Stopping
     * the retrieval is the whole of what this collection is for. Removing the attachment as well
     * stays {@code DELETE /attachments/{attachmentId}}, which reaches this row through
     * {@link #deleteByChatAttachmentId}.
     */
    @Transactional
    public void deleteForChat(UUID documentId, UUID chatId) {
        delete(chatDocument(documentId, chatId));
    }

    @Transactional
    public IngestedDocumentSummary refreshGlobal(UUID documentId) {
        return refresh(globalDocument(documentId));
    }

    @Transactional
    public IngestedDocumentSummary refreshForUser(UUID documentId, UUID userId) {
        return refresh(userDocument(documentId, userId));
    }

    /**
     * Re-queues one of a conversation's documents. An attachment-sourced row has no bytes of its own
     * — {@link #beginChatIngestion} leaves {@code fileData} empty on purpose — so re-reading them is
     * {@code DocumentService}'s job when the queue reaches the row, the same way a {@code URI}
     * document is re-fetched rather than re-uploaded.
     */
    @Transactional
    public IngestedDocumentSummary refreshForChat(UUID documentId, UUID chatId) {
        return refresh(chatDocument(documentId, chatId));
    }

    @Transactional
    public IngestedDocumentSummary renameGlobal(UUID documentId, String fileName) {
        String newFileName = validName(fileName);

        return rename(globalDocument(documentId), newFileName);
    }

    @Transactional
    public IngestedDocumentSummary renameForUser(UUID documentId, UUID userId, String fileName) {
        String newFileName = validName(fileName);

        return rename(userDocument(documentId, userId), newFileName);
    }

    @Transactional
    public IngestedDocumentSummary renameForChat(UUID documentId, UUID chatId, String fileName) {
        String newFileName = validName(fileName);

        return rename(chatDocument(documentId, chatId), newFileName);
    }

    /**
     * Queues an uploaded document at the scope its collection names, owned by {@code ownerId}.
     * <p>
     * Both are arguments rather than anything this method works out for itself: the scope is which
     * endpoint the upload arrived at, and the owner is the {@code {userId}} path segment the
     * controller has already checked against the caller. There is no call site on which either can
     * be omitted, which is what stops a document being written with no scope recorded.
     */
    @Transactional
    public IngestedDocumentSummary queue(MultipartFile multipartFile, RetrievalScope scope, UUID ownerId) {
        if (scope == RetrievalScope.CHAT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CHAT documents belong to a conversation, not an owner; use queueForChat");
        }

        log.debug("Queuing document at {} scope.", scope);

        Resource newFileResource = multipartFile.getResource();
        String fileName = newFileResource.getFilename();

        // Deduplication by file name is only safe between shared documents. Two users uploading
        // "notes.pdf" mean two different documents, and reusing one row for both would hand the
        // second uploader the first one's. The scope restriction is in the query.
        if (scope == RetrievalScope.GLOBAL) {
            Optional<IngestedDocument> existing = ingestedDocumentRepository.findGlobalByFileName(fileName);

            if (existing.isPresent()) {
                return summary(existing.get());
            }
        }

        IngestedDocument ingestedDocument = ingestedDocument(multipartFile, fileName, newFileResource);

        ingestedDocument.setScope(scope);
        ingestedDocument.setUserId(scope == RetrievalScope.USER ? ownerId : null);

        return summary(save(ingestedDocument));
    }

    /**
     * Queues a document uploaded straight to a conversation, rather than attached to one of its
     * messages.
     * <p>
     * Separate from {@link #queue(MultipartFile, RetrievalScope, UUID)} because the owning key is
     * different in kind: the other two collections are owned by a user or by nobody, and this one is
     * owned by a chat. Keeping {@code scope} out of the signature entirely is what makes "queued at
     * the wrong scope" unexpressible here, the same reason neither collection's controller takes a
     * scope parameter.
     * <p>
     * Unlike {@link #beginChatIngestion}, the bytes are stored on this row: there is no attachment
     * holding the only copy, so the row is the copy — which is also what lets {@code refresh} re-run
     * over it. It goes in at {@link DocumentStatus#QUEUED} and is picked up by the ordinary poller,
     * because nobody is waiting on a turn for it.
     * <p>
     * {@code CHAT_ID} is written into the metadata map as well as the column. The column is what the
     * collection queries; the key is what {@code DocumentService} stamps chunks from and what the
     * teardown queries match, and a row carrying only one of the two would be invisible to half the
     * code that has to find it.
     */
    @Transactional
    public IngestedDocumentSummary queueForChat(MultipartFile multipartFile, UUID chatId, UUID userId) {
        log.debug("Queuing document for chat {}", chatId);

        Resource newFileResource = multipartFile.getResource();
        String fileName = newFileResource.getFilename();

        IngestedDocument ingestedDocument = ingestedDocument(multipartFile, fileName, newFileResource);

        ingestedDocument.setScope(RetrievalScope.CHAT);
        ingestedDocument.setUserId(userId);
        ingestedDocument.setChatId(chatId);
        ingestedDocument.getMetadata().put(IngestedDocument.CHAT_ID, chatId.toString());

        return summary(save(ingestedDocument));
    }

    /**
     * Creates the tracked row for a chat document attachment, already
     * {@link DocumentStatus#IN_PROGRESS}, for the caller to embed against.
     * <p>
     * Internal to {@code ChatDocumentIngestionService}, and deliberately separate from
     * {@link #queue(MultipartFile, RetrievalScope, UUID)}: that method is what the two document
     * collections call, and it rejects {@code CHAT} on purpose. Relaxing its guard rather than
     * adding this method would make {@code CHAT} a scope a REST caller could name, which is the one
     * thing the guard exists to prevent.
     * <p>
     * Not called a queue because nothing waits. The row is written and ingestion begins in this same
     * call, on this same thread, so it never passes through {@link DocumentStatus#QUEUED} — the
     * poller behind {@code StatusHistoryService.processQueued()} can therefore never pick it up and
     * ingest it a second time, and one {@code status_history} row is written where saving at
     * {@code QUEUED} and updating straight after would write two.
     * <p>
     * {@code fileData} is left empty, the same convention a not-yet-fetched URI row uses. The bytes
     * are already on the {@code chat_attachment} row this document was read from; a second copy here
     * would double the storage and be free to drift from the one the attachment actually serves.
     */
    @Transactional
    public IngestedDocument beginChatIngestion(UUID chatId,
                                               UUID userId,
                                               UUID chatAttachmentId,
                                               String fileName,
                                               String contentType) {
        log.debug("Beginning chat ingestion of attachment {} on chat {}", chatAttachmentId, chatId);

        return save(chatIngestedDocument(chatId, userId, chatAttachmentId, fileName, contentType));
    }

    public IngestedDocument save(IngestedDocument ingestedDocument) {
        ingestedDocument.setCreated(ZonedDateTime.now());
        ingestedDocument.setUpdated(ZonedDateTime.now());

        ingestedDocument = ingestedDocumentRepository.save(ingestedDocument);

        StatusHistory statusHistory = new StatusHistory();
        statusHistory.setDocumentStatus(ingestedDocument.getDocumentStatus());
        statusHistory.setDocumentId(ingestedDocument.getId());
        statusHistory.setTimestamp(ZonedDateTime.now());

        statusHistoryRepository.save(statusHistory);

        return ingestedDocument;
    }

    public IngestedDocument update(IngestedDocument ingestedDocument, DocumentStatus documentStatus) {
        log.info("Updating ingested document: {}", ingestedDocument.getId());
        ingestedDocument.setUpdated(ZonedDateTime.now());

        if (!documentStatus.equals(ingestedDocument.getDocumentStatus())) {
            log.info("Updating document id: {} to status: {}", ingestedDocument.getId(), documentStatus);

            StatusHistory statusHistory = new StatusHistory();
            statusHistory.setDocumentStatus(documentStatus);
            statusHistory.setDocumentId(ingestedDocument.getId());
            statusHistory.setTimestamp(ZonedDateTime.now());

            statusHistoryRepository.save(statusHistory);

            ingestedDocument.setDocumentStatus(documentStatus);
        }

        return ingestedDocumentRepository.save(ingestedDocument);
    }

    /**
     * Unscoped, and deliberately not reachable from a controller. Its callers are the ingestion
     * pipeline itself — {@code StatusHistoryService} and {@code DocumentService} working through a
     * queue — which have no request and no scope to check against.
     */
    public IngestedDocument get(UUID documentId) {
        log.info("Getting document id: {}", documentId);
        IngestedDocument ingestedDocument = ingestedDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ChatException("Error getting ingested document"));

        ingestedDocument.setDocumentStatus(latestStatus(documentId));

        return ingestedDocument;
    }

    @Transactional
    public List<IngestedDocument> findByConfluencePageId(String confluencePageId) {
        log.debug("Finding ingested documents by confluence page id: {}", confluencePageId);

        return ingestedDocumentRepository.findByConfluenceId(confluencePageId)
                .orElse(null);
    }

    @Transactional
    public List<String> findConfluencePageIds() {
        log.debug("Finding all tracked confluence page ids.");

        return ingestedDocumentRepository.findConfluencePageIds();
    }

    @Transactional
    public List<IngestedDocument> findBySourceUri(String sourceUri) {
        log.debug("Finding ingested documents by source uri: {}", sourceUri);

        return ingestedDocumentRepository.findBySourceUri(sourceUri)
                .orElse(List.of());
    }

    @Transactional
    public void delete(IngestedDocument ingestedDocument) {
        log.info("Deleting ingested document: {}", ingestedDocument.getId());

        List<VectorDocument> vectorDocuments = vectorStoreService.findByIngestedDocumentId(ingestedDocument.getId());
        vectorStoreService.delete(vectorDocuments);

        statusHistoryRepository.deleteByDocumentId(ingestedDocument.getId());
        ingestedDocumentRepository.delete(ingestedDocument);
    }

    /**
     * Discards the row one chat attachment opened, its chunks and status history with it.
     * <p>
     * Named and shaped after {@link VectorStoreService#deleteByChatAttachmentId} so that
     * {@code ChatAttachmentService} calls a matching pair rather than reaching into
     * {@link IngestedDocumentRepository} itself, and delegating to {@link #delete(IngestedDocument)}
     * rather than repeating the three-step teardown.
     * <p>
     * Whatever status the row is in. A document that failed extraction, or one whose ingest a delete
     * raced, has no chunk count to test against and is exactly the row that would leak — so the
     * question asked is whether a row exists, not whether it finished.
     */
    @Transactional
    public void deleteByChatAttachmentId(UUID chatAttachmentId) {
        List<IngestedDocument> ingestedDocuments =
                ingestedDocumentRepository.findByChatAttachmentId(chatAttachmentId.toString());

        for (IngestedDocument ingestedDocument : ingestedDocuments) {
            delete(ingestedDocument);
        }
    }

    /**
     * The same teardown for every document attached to one conversation. A row at a time, so each
     * one's chunks and status history go with it — deleting the rows in bulk would leave both
     * behind.
     */
    @Transactional
    public void deleteByChatId(UUID chatId) {
        List<IngestedDocument> ingestedDocuments =
                ingestedDocumentRepository.findByChatId(chatId.toString());

        for (IngestedDocument ingestedDocument : ingestedDocuments) {
            delete(ingestedDocument);
        }
    }

    private IngestedDocument globalDocument(UUID documentId) {
        return ingestedDocumentRepository.findGlobalById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_REASON));
    }

    private IngestedDocument userDocument(UUID documentId, UUID userId) {
        return ingestedDocumentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_REASON));
    }

    private IngestedDocument chatDocument(UUID documentId, UUID chatId) {
        return ingestedDocumentRepository.findByIdAndChatId(documentId, chatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_REASON));
    }

    private IngestedDocumentSummary refresh(IngestedDocument ingestedDocument) {
        log.info("Refreshing ingested document id: {}", ingestedDocument.getId());

        backfillMetadata(ingestedDocument);

        List<VectorDocument> vectorDocuments =
                vectorStoreService.findByIngestedDocumentId(ingestedDocument.getId());
        vectorStoreService.delete(vectorDocuments);

        return summary(update(ingestedDocument, DocumentStatus.QUEUED));
    }

    /**
     * The one update operation either collection offers, and the only part of it that differs
     * between scopes is the fetch that produced {@code ingestedDocument}.
     * <p>
     * The stored file name is what the vector store's chunks were enriched under, but renaming does
     * not re-embed: the chunks keep the name they were written with until a {@code refresh}, which
     * is the operation that exists for making the index match the row again.
     */
    private IngestedDocumentSummary rename(IngestedDocument ingestedDocument, String fileName) {
        log.info("Renaming ingested document {} to {}", ingestedDocument.getId(), fileName);

        ingestedDocument.setFileName(fileName);
        ingestedDocument.setUpdated(ZonedDateTime.now());

        return summary(ingestedDocumentRepository.save(ingestedDocument));
    }

    private static String validName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A file name is required");
        }

        return fileName.strip();
    }

    /**
     * One status query for the whole page. Asking per row is what listing did before it was
     * paginated, and it was already the expensive half of rendering the list.
     */
    private Page<IngestedDocumentSummary> summaries(Page<IngestedDocument> ingestedDocuments) {
        if (ingestedDocuments.isEmpty()) {
            return ingestedDocuments.map(ingestedDocument -> summary(ingestedDocument, null));
        }

        List<UUID> documentIds = ingestedDocuments.getContent().stream()
                .map(IngestedDocument::getId)
                .toList();

        Map<UUID, DocumentStatus> statuses = new LinkedHashMap<>();

        for (DocumentStatusEntry entry : statusHistoryRepository.findLatestStatuses(documentIds)) {
            statuses.putIfAbsent(entry.documentId(), entry.documentStatus());
        }

        return ingestedDocuments.map(ingestedDocument ->
                summary(ingestedDocument, statuses.get(ingestedDocument.getId())));
    }

    private IngestedDocumentSummary summary(IngestedDocument ingestedDocument) {
        DocumentStatus documentStatus = ingestedDocument.getDocumentStatus() != null
                ? ingestedDocument.getDocumentStatus()
                : latestStatus(ingestedDocument.getId());

        return summary(ingestedDocument, documentStatus);
    }

    private static IngestedDocumentSummary summary(IngestedDocument ingestedDocument, DocumentStatus documentStatus) {
        return IngestedDocumentSummary.of(ingestedDocument, documentStatus);
    }

    private DocumentStatus latestStatus(UUID documentId) {
        return statusHistoryRepository.findByDocumentId(documentId).stream()
                .findFirst()
                .orElse(null);
    }

    private static void backfillMetadata(IngestedDocument ingestedDocument) {
        if (ingestedDocument.getDocumentSource() == null) {
            ingestedDocument.setDocumentSource(DocumentSource.USER);
        }

        if (ingestedDocument.getMetadata() == null) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(IngestedDocument.ORIGINAL_FILE_NAME, ingestedDocument.getFileName());
            metadata.put(FILE_SIZE_BYTES, ingestedDocument.getFileData().length);
            ingestedDocument.setMetadata(metadata);
        }
    }

    private static IngestedDocument ingestedDocument(MultipartFile multipartFile, String fileName, Resource newFileResource) {
        String contentType = multipartFile.getContentType();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(IngestedDocument.ORIGINAL_FILE_NAME, fileName);
        metadata.put(FILE_SIZE_BYTES, multipartFile.getSize());

        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setDocumentStatus(DocumentStatus.QUEUED);
        ingestedDocument.setFileName(fileName);
        ingestedDocument.setContentType(contentType);
        ingestedDocument.setDocumentSource(DocumentSource.USER);
        ingestedDocument.setMetadata(metadata);

        try (InputStream inputStream = newFileResource.getInputStream()) {
            byte[] fileContent = inputStream.readAllBytes();
            ingestedDocument.setFileData(fileContent);
        } catch (IOException e) {
            throw new ChatException("Failed to upload document", e);
        }

        return ingestedDocument;
    }

    /**
     * Both ids go in as strings, so a later lookup by either compares against what the JSON
     * metadata column holds rather than a shape it never stores. They are entity metadata, not the
     * chunk metadata a retrieval filter reads — a separate map, named after
     * {@code RetrievalMetadata} only so the two do not drift apart.
     */
    private static IngestedDocument chatIngestedDocument(UUID chatId,
                                                         UUID userId,
                                                         UUID chatAttachmentId,
                                                         String fileName,
                                                         String contentType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(IngestedDocument.ORIGINAL_FILE_NAME, fileName);
        metadata.put(IngestedDocument.CHAT_ID, chatId.toString());
        metadata.put(IngestedDocument.CHAT_ATTACHMENT_ID, chatAttachmentId.toString());

        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setDocumentStatus(DocumentStatus.IN_PROGRESS);
        ingestedDocument.setFileName(fileName);
        ingestedDocument.setContentType(contentType);
        ingestedDocument.setFileData(new byte[0]);
        ingestedDocument.setDocumentSource(DocumentSource.CHAT);
        ingestedDocument.setScope(RetrievalScope.CHAT);
        ingestedDocument.setUserId(userId);
        ingestedDocument.setChatId(chatId);
        ingestedDocument.setMetadata(metadata);

        return ingestedDocument;
    }
}
