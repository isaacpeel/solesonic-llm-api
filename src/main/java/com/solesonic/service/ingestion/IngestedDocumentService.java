package com.solesonic.service.ingestion;

import com.solesonic.exception.ChatException;
import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentEntitlement;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.DocumentStatusEntry;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.IngestedDocumentContent;
import com.solesonic.model.ingestion.IngestedDocumentSummary;
import com.solesonic.model.ingestion.StatusHistory;
import com.solesonic.model.ingestion.VectorDocument;
import com.solesonic.model.rag.DocumentPrincipal;
import com.solesonic.model.rag.GrantKind;
import com.solesonic.repository.chat.ChatAttachmentRepository;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.repository.ingestion.DocumentEntitlementRepository;
import com.solesonic.repository.ingestion.IngestedDocumentContentRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.model.ingestion.IngestedDocument.FILE_SIZE_BYTES;

/**
 * Ingested documents, in one method family parameterised by {@link DocumentPrincipal} rather than
 * three families that each knew their own scope.
 * <p>
 * The collections above this are still separate — which endpoint a request arrived at is what
 * decides the principal, so "created at the wrong scope" stays unexpressible. What collapsed is
 * everything below that decision: a fetch, a delete, a rename and a refresh differ only in which
 * grant they require, and that is now an argument instead of a method name. Ownership remains a
 * {@code where} clause, so there is still no fetch-then-compare anywhere.
 */
@Service
public class IngestedDocumentService {
    private static final Logger log = LoggerFactory.getLogger(IngestedDocumentService.class);

    private static final String NOT_FOUND_REASON = "No such ingested document";

    private final IngestedDocumentRepository ingestedDocumentRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final VectorStoreService vectorStoreService;
    private final DocumentEntitlementService documentEntitlementService;
    private final DocumentEntitlementRepository documentEntitlementRepository;
    private final IngestedDocumentContentRepository ingestedDocumentContentRepository;
    private final ChatRepository chatRepository;
    private final ChatAttachmentRepository chatAttachmentRepository;

    public IngestedDocumentService(IngestedDocumentRepository ingestedDocumentRepository,
                                   StatusHistoryRepository statusHistoryRepository,
                                   VectorStoreService vectorStoreService,
                                   DocumentEntitlementService documentEntitlementService,
                                   DocumentEntitlementRepository documentEntitlementRepository,
                                   IngestedDocumentContentRepository ingestedDocumentContentRepository,
                                   ChatRepository chatRepository,
                                   ChatAttachmentRepository chatAttachmentRepository) {
        this.ingestedDocumentRepository = ingestedDocumentRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.vectorStoreService = vectorStoreService;
        this.documentEntitlementService = documentEntitlementService;
        this.documentEntitlementRepository = documentEntitlementRepository;
        this.ingestedDocumentContentRepository = ingestedDocumentContentRepository;
        this.chatRepository = chatRepository;
        this.chatAttachmentRepository = chatAttachmentRepository;
    }

    public Page<IngestedDocumentSummary> listGlobal(Pageable pageable) {
        log.debug("Listing global ingested documents page {}", pageable.getPageNumber());

        return summaries(retrievableBy(DocumentPrincipal.global(), pageable));
    }

    public Page<IngestedDocumentSummary> listForUser(UUID userId, Pageable pageable) {
        log.debug("Listing ingested documents for user {} page {}", userId, pageable.getPageNumber());

        return summaries(retrievableBy(DocumentPrincipal.user(userId), pageable));
    }

    /**
     * Every document this user has uploaded to any conversation, optionally narrowed to one (§6.2).
     * <p>
     * The question the whole entitlement model exists to make askable. Before, a document's owner
     * and its conversation were the same fact, so "mine across all chats" had to be reconstructed by
     * joining through {@code chat}; now it is a {@code MANAGE} grant intersected with the existence
     * of any {@code CHAT} retrieve grant.
     *
     * @param chatId null for every conversation
     */
    public Page<IngestedDocumentSummary> listChatDocuments(UUID userId,
                                                           UUID chatId,
                                                           DocumentStatus documentStatus,
                                                           Pageable pageable) {
        log.debug("Listing chat documents managed by user {} chat {} status {}", userId, chatId, documentStatus);

        Page<IngestedDocument> ingestedDocuments = ingestedDocumentRepository.findAllChatDocumentsManagedBy(
                userId.toString(),
                chatId == null ? null : chatId.toString(),
                documentStatus,
                pageable);

        return summaries(ingestedDocuments);
    }

    public IngestedDocumentSummary getGlobal(UUID documentId) {
        return summary(retrievable(documentId, DocumentPrincipal.global()));
    }

    public IngestedDocumentSummary getForUser(UUID documentId, UUID userId) {
        return summary(retrievable(documentId, DocumentPrincipal.user(userId)));
    }

    /**
     * A chat document is fetched by the {@code MANAGE} grant, not the {@code RETRIEVE} one: it is
     * retrievable by the conversation but managed by the person who uploaded it, so asking the
     * retrieve question would hide a user's own document from the endpoints that manage it.
     */
    public IngestedDocumentSummary getChatDocument(UUID documentId, UUID userId) {
        return summary(manageable(documentId, DocumentPrincipal.user(userId)));
    }

    @Transactional
    public void deleteGlobal(UUID documentId) {
        delete(retrievable(documentId, DocumentPrincipal.global()));
    }

    @Transactional
    public void deleteForUser(UUID documentId, UUID userId) {
        delete(retrievable(documentId, DocumentPrincipal.user(userId)));
    }

    /**
     * Removes one document from a conversation, its chunks and status history with it.
     * <p>
     * The {@code chat_attachment} row a document arrived on is deliberately left alone: the message
     * that carried it is part of the conversation's history, and deleting the attachment would
     * rewrite that history rather than stop the document being retrieved. Removing the attachment as
     * well stays {@code DELETE /attachments/{attachmentId}}.
     */
    @Transactional
    public void deleteChatDocument(UUID documentId, UUID userId) {
        delete(manageable(documentId, DocumentPrincipal.user(userId)));
    }

    @Transactional
    public IngestedDocumentSummary refreshGlobal(UUID documentId) {
        return refresh(retrievable(documentId, DocumentPrincipal.global()));
    }

    @Transactional
    public IngestedDocumentSummary refreshForUser(UUID documentId, UUID userId) {
        return refresh(retrievable(documentId, DocumentPrincipal.user(userId)));
    }

    @Transactional
    public IngestedDocumentSummary refreshChatDocument(UUID documentId, UUID userId) {
        return refresh(manageable(documentId, DocumentPrincipal.user(userId)));
    }

    // The name is validated BEFORE the document is fetched, in all three. Inlining validName(...)
    // into the rename(...) argument list would reverse that -- Java evaluates arguments left to
    // right -- and a blank name would come back as the 404 of whichever document was not found
    // rather than the 400 it is.

    @Transactional
    public IngestedDocumentSummary renameGlobal(UUID documentId, String fileName) {
        String newFileName = validName(fileName);

        return rename(retrievable(documentId, DocumentPrincipal.global()), newFileName);
    }

    @Transactional
    public IngestedDocumentSummary renameForUser(UUID documentId, UUID userId, String fileName) {
        String newFileName = validName(fileName);

        return rename(retrievable(documentId, DocumentPrincipal.user(userId)), newFileName);
    }

    @Transactional
    public IngestedDocumentSummary renameChatDocument(UUID documentId, UUID userId, String fileName) {
        String newFileName = validName(fileName);

        return rename(manageable(documentId, DocumentPrincipal.user(userId)), newFileName);
    }

    /**
     * Queues an uploaded document into the shared corpus.
     * <p>
     * Its only manager is the {@code rag-admin} role — the uploading administrator keeps no personal
     * grant, and {@code granted_by} is what records who added it.
     */
    @Transactional
    public IngestedDocumentSummary queueGlobal(MultipartFile multipartFile, UUID uploaderId) {
        Resource resource = multipartFile.getResource();

        // Deduplication by file name is only safe between shared documents. Two users uploading
        // "notes.pdf" mean two different documents, and reusing one row for both would hand the
        // second uploader the first one's. The restriction is in the query.
        return ingestedDocumentRepository.findGlobalByFileName(resource.getFilename())
                .map(this::summary)
                .orElseGet(() -> queue(multipartFile,
                        DocumentPrincipal.global(),
                        DocumentPrincipal.ragAdmin(),
                        uploaderId,
                        Map.of()));
    }

    @Transactional
    public IngestedDocumentSummary queueForUser(MultipartFile multipartFile, UUID userId) {
        DocumentPrincipal owner = DocumentPrincipal.user(userId);

        return queue(multipartFile, owner, owner, userId, Map.of());
    }

    /**
     * Queues a document uploaded straight to a conversation, rather than attached to one of its
     * messages.
     * <p>
     * Retrievable by the chat, managed by the uploader — the split that makes "all my chat
     * documents" answerable. The bytes are stored, because no attachment holds a copy, which is also
     * what lets {@code refresh} re-run over it. It goes in at {@link DocumentStatus#QUEUED} and is
     * picked up by the ordinary poller, which it genuinely is now:
     * {@code StatusHistoryRepository.findQueued} no longer filters such a row out.
     */
    @Transactional
    public IngestedDocumentSummary queueForChat(MultipartFile multipartFile, UUID chatId, UUID userId) {
        log.debug("Queuing document for chat {}", chatId);

        return queue(multipartFile,
                DocumentPrincipal.chat(chatId),
                DocumentPrincipal.user(userId),
                userId,
                Map.of(IngestedDocument.CHAT_ID, chatId.toString()));
    }

    /**
     * Creates the tracked row for a chat document attachment, already
     * {@link DocumentStatus#IN_PROGRESS}, for the caller to embed against.
     * <p>
     * Internal to {@code ChatDocumentIngestionService}. Not called a queue because nothing waits:
     * the row is written and ingestion begins in this same call, on this same thread, so it never
     * passes through {@link DocumentStatus#QUEUED} and the poller can never pick it up and ingest it
     * a second time.
     * <p>
     * No content row is written. The bytes are already on the {@code chat_attachment} row this
     * document was read from; a second copy here would double the storage and be free to drift from
     * the one the attachment actually serves.
     */
    @Transactional
    public IngestedDocument beginChatIngestion(UUID chatId,
                                               UUID userId,
                                               UUID chatAttachmentId,
                                               String fileName,
                                               String contentType) {
        log.debug("Beginning chat ingestion of attachment {} on chat {}", chatAttachmentId, chatId);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(IngestedDocument.ORIGINAL_FILE_NAME, fileName);
        metadata.put(IngestedDocument.CHAT_ID, chatId.toString());
        metadata.put(IngestedDocument.CHAT_ATTACHMENT_ID, chatAttachmentId.toString());

        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setDocumentStatus(DocumentStatus.IN_PROGRESS);
        ingestedDocument.setFileName(fileName);
        ingestedDocument.setContentType(contentType);
        ingestedDocument.setDocumentSource(DocumentSource.CHAT);
        ingestedDocument.setMetadata(metadata);

        IngestedDocument saved = save(ingestedDocument);

        documentEntitlementService.grantOwnership(saved.getId(),
                DocumentPrincipal.chat(chatId),
                DocumentPrincipal.user(userId),
                userId);

        return saved;
    }

    /**
     * Moves a chat document into the uploader's own library.
     * <p>
     * Management does not change — the caller already manages it, which is how they were allowed to
     * ask. What changes is the audience: the conversation loses it and the user gains it.
     */
    @Transactional
    public IngestedDocumentSummary promoteToUser(UUID documentId, UUID userId) {
        IngestedDocument ingestedDocument = promotable(documentId, userId);

        promote(ingestedDocument, List.of(DocumentPrincipal.user(userId)), userId);

        return summary(ingestedDocument);
    }

    /**
     * Moves a document — chat or user-scoped — into the shared corpus.
     * <p>
     * {@link #promotable(UUID, UUID)} only requires the caller to manage the document, which a
     * user-scoped document's own uploader already does ({@link #queueForUser} grants {@code MANAGE}
     * and {@code RETRIEVE} to the same principal), so {@code IngestedUserDocumentController}'s
     * {@code promote/global} calls this directly rather than duplicating it.
     * <p>
     * Management changes too: a global document's only manager is the {@code rag-admin} role, so the
     * promoting administrator keeps no personal grant and {@code granted_by} records who did it.
     */
    @Transactional
    public IngestedDocumentSummary promoteToGlobal(UUID documentId, UUID userId) {
        IngestedDocument ingestedDocument = promotable(documentId, userId);

        // queueGlobal de-duplicates an upload by returning the existing row. Promotion cannot do
        // that -- it already has a different row, and silently merging two documents would discard
        // one. The collision is the caller's to resolve.
        Optional<IngestedDocument> collision =
                ingestedDocumentRepository.findGlobalByFileName(ingestedDocument.getFileName());

        if (collision.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A shared document named '%s' already exists: %s"
                            .formatted(ingestedDocument.getFileName(), collision.get().getId()));
        }

        promote(ingestedDocument, List.of(DocumentPrincipal.global()), userId);

        documentEntitlementService.replaceManageGrants(documentId, List.of(DocumentPrincipal.ragAdmin()), userId);

        return summary(ingestedDocument);
    }

    /**
     * Saves a document built by an ingestion path of its own — URI and Confluence — together with
     * the grants that make it reachable and, where there are any, its bytes.
     * <p>
     * One call rather than three, for the reason {@code grantOwnership} is one call: a row without
     * grants is retrievable and manageable by nobody, and every historical gap in this table came
     * from a new ingestion path doing part of what the others did. There is no ordering here a
     * caller can get wrong.
     *
     * @param content null or empty where the bytes are fetched later, as a URI document's are
     */
    @Transactional
    public IngestedDocument saveWithOwnership(IngestedDocument ingestedDocument,
                                              DocumentPrincipal retrievableBy,
                                              DocumentPrincipal managedBy,
                                              UUID grantedBy,
                                              byte[] content) {
        IngestedDocument saved = save(ingestedDocument);

        documentEntitlementService.grantOwnership(saved.getId(), retrievableBy, managedBy, grantedBy);

        if (content != null && content.length > 0) {
            writeContent(saved.getId(), content);
        }

        return saved;
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
     * queue — which have no request and no principal to check against.
     */
    public IngestedDocument get(UUID documentId) {
        log.info("Getting document id: {}", documentId);
        IngestedDocument ingestedDocument = ingestedDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ChatException("Error getting ingested document"));

        ingestedDocument.setDocumentStatus(latestStatus(documentId));

        return ingestedDocument;
    }

    /**
     * The stored bytes of one document, where it has any. Absent for a URI document before its fetch
     * and for a chat attachment whose only copy is on the attachment row — which is the same "bytes
     * live elsewhere" state an empty {@code byte[0]} used to stand in for.
     */
    public Optional<byte[]> content(UUID documentId) {
        return ingestedDocumentContentRepository.findDataByIngestedDocumentId(documentId);
    }

    @Transactional
    public void storeContent(UUID documentId, byte[] data) {
        writeContent(documentId, data);
    }

    /**
     * The write itself, without a transaction boundary of its own.
     * <p>
     * Callers inside this class use it directly: a self-invocation of {@link #storeContent} would not
     * pass through the proxy, so its {@code @Transactional} would have no effect and would only read
     * as though it did. They already run inside one.
     */
    private void writeContent(UUID documentId, byte[] data) {
        ingestedDocumentContentRepository.save(new IngestedDocumentContent(documentId, data));
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

    /**
     * The single place a document is dropped, so no caller can remove a row and leave its chunks
     * still answering questions.
     * <p>
     * The content row and the grant rows go by {@code ON DELETE CASCADE} rather than by code.
     */
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
     * The same teardown for every document that came from one conversation. A row at a time, so each
     * one's chunks and status history go with it.
     * <p>
     * Keyed on provenance, so a document promoted out of this conversation — which cleared that key
     * — is deliberately not reached. That is the point of promotion surviving its origin chat.
     */
    @Transactional
    public void deleteByChatId(UUID chatId) {
        List<IngestedDocument> ingestedDocuments =
                ingestedDocumentRepository.findByChatId(chatId.toString());

        for (IngestedDocument ingestedDocument : ingestedDocuments) {
            delete(ingestedDocument);
        }
    }

    /**
     * The document a promotion may act on: managed by the caller, and finished.
     * <p>
     * Mid-ingest is refused rather than queued behind, because the chunks are being stamped right
     * now — a promotion would race the very metadata it is trying to rewrite, and some chunks would
     * keep the old audience.
     */
    private IngestedDocument promotable(UUID documentId, UUID userId) {
        IngestedDocument ingestedDocument = manageable(documentId, DocumentPrincipal.user(userId));

        DocumentStatus documentStatus = latestStatus(documentId);

        if (documentStatus != DocumentStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Document %s is %s; only a COMPLETED document can be promoted"
                            .formatted(documentId, documentStatus));
        }

        return ingestedDocument;
    }

    private void promote(IngestedDocument ingestedDocument, List<DocumentPrincipal> audience, UUID promotedBy) {
        UUID documentId = ingestedDocument.getId();

        log.info("Promoting document {}", documentId);

        materializeContent(ingestedDocument);

        // Off CHAT, or the next refresh re-enters loadChatAttachmentContent and throws for want of
        // an attachment this document no longer belongs to.
        if (ingestedDocument.getDocumentSource() == DocumentSource.CHAT) {
            ingestedDocument.setDocumentSource(DocumentSource.USER);
        }

        documentEntitlementService.replaceRetrieveGrants(documentId, audience, promotedBy);

        // Provenance goes too, on both the row and its chunks. Teardown is keyed on it, so a
        // promoted document that kept CHAT_ID would be deleted along with the conversation it was
        // promoted out of -- leaving a COMPLETED document that retrieves nothing.
        Map<String, Object> metadata = ingestedDocument.getMetadata();

        if (metadata != null) {
            metadata.remove(IngestedDocument.CHAT_ID);
            metadata.remove(IngestedDocument.CHAT_ATTACHMENT_ID);
        }

        ingestedDocument.setUpdated(ZonedDateTime.now());
        ingestedDocumentRepository.save(ingestedDocument);

        vectorStoreService.promoteChunks(documentId, documentEntitlementService.retrievalKeys(documentId));
    }

    /**
     * Gives a promoted document its own copy of the bytes, where the only copy was on the attachment
     * it arrived as.
     * <p>
     * Without this the document dies with the conversation — the attachment goes when the chat does
     * — and {@code refresh} has nothing to re-read in the meantime.
     */
    private void materializeContent(IngestedDocument ingestedDocument) {
        UUID documentId = ingestedDocument.getId();

        if (ingestedDocumentContentRepository.existsByIngestedDocumentId(documentId)) {
            return;
        }

        Object chatAttachmentId = ingestedDocument.getMetadata() == null
                ? null
                : ingestedDocument.getMetadata().get(IngestedDocument.CHAT_ATTACHMENT_ID);

        if (chatAttachmentId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Document " + documentId + " has no stored content and no attachment to read it from");
        }

        ChatAttachment chatAttachment = chatAttachmentRepository
                .findById(UUID.fromString(chatAttachmentId.toString()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Document " + documentId + " has no attachment left to copy its content from"));

        ingestedDocumentContentRepository.save(
                new IngestedDocumentContent(documentId, chatAttachment.getFileData()));
    }

    private Page<IngestedDocument> retrievableBy(DocumentPrincipal principal, Pageable pageable) {
        return ingestedDocumentRepository.findAllRetrievableBy(principal.type(), principal.id(), pageable);
    }

    private IngestedDocument retrievable(UUID documentId, DocumentPrincipal principal) {
        return ingestedDocumentRepository.findRetrievableBy(documentId, principal.type(), principal.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_REASON));
    }

    private IngestedDocument manageable(UUID documentId, DocumentPrincipal principal) {
        return ingestedDocumentRepository.findManageableBy(documentId, principal.type(), principal.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_REASON));
    }

    /**
     * The one path an uploaded document takes into the table, whichever collection it arrived at.
     * <p>
     * The grant is written immediately after the row and before the bytes, because a row with no
     * grant is retrievable and manageable by nobody — the "row nothing can reach" that {@code V3_14}
     * had to repair. Both principals are arguments with no default, so no call site can omit either.
     */
    private IngestedDocumentSummary queue(MultipartFile multipartFile,
                                          DocumentPrincipal retrievableBy,
                                          DocumentPrincipal managedBy,
                                          UUID uploaderId,
                                          Map<String, Object> extraMetadata) {
        Resource resource = multipartFile.getResource();
        String fileName = resource.getFilename();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(IngestedDocument.ORIGINAL_FILE_NAME, fileName);
        metadata.put(FILE_SIZE_BYTES, multipartFile.getSize());
        metadata.putAll(extraMetadata);

        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setDocumentStatus(DocumentStatus.QUEUED);
        ingestedDocument.setFileName(fileName);
        ingestedDocument.setContentType(multipartFile.getContentType());
        ingestedDocument.setDocumentSource(DocumentSource.USER);
        ingestedDocument.setMetadata(metadata);

        IngestedDocument saved = save(ingestedDocument);

        documentEntitlementService.grantOwnership(saved.getId(), retrievableBy, managedBy, uploaderId);

        try (InputStream inputStream = resource.getInputStream()) {
            writeContent(saved.getId(), inputStream.readAllBytes());
        } catch (IOException e) {
            throw new ChatException("Failed to upload document", e);
        }

        return summary(saved);
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
     * The one update operation any collection offers, and the only part of it that differs between
     * principals is the fetch that produced {@code ingestedDocument}.
     * <p>
     * Renaming does not re-embed: the chunks keep the name they were enriched under until a
     * {@code refresh}, which is the operation that exists for making the index match the row again.
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
     * One status query, one grant query and one chat query for the whole page. Asking per row is
     * what listing did before it was paginated, and it was already the expensive half of rendering
     * the list.
     */
    private Page<IngestedDocumentSummary> summaries(Page<IngestedDocument> ingestedDocuments) {
        if (ingestedDocuments.isEmpty()) {
            return ingestedDocuments.map(ingestedDocument ->
                    IngestedDocumentSummary.of(ingestedDocument, null, List.of()));
        }

        List<UUID> documentIds = ingestedDocuments.getContent().stream()
                .map(IngestedDocument::getId)
                .toList();

        Map<UUID, DocumentStatus> statuses = new LinkedHashMap<>();

        for (DocumentStatusEntry entry : statusHistoryRepository.findLatestStatuses(documentIds)) {
            statuses.putIfAbsent(entry.documentId(), entry.documentStatus());
        }

        Map<UUID, List<DocumentPrincipal>> principals = new LinkedHashMap<>();

        for (DocumentEntitlement entitlement : documentEntitlementRepository
                .findByIngestedDocumentIdInAndGrantKind(documentIds, GrantKind.RETRIEVE)) {
            principals.computeIfAbsent(entitlement.getIngestedDocumentId(), _ -> new ArrayList<>())
                    .add(entitlement.principal());
        }

        Map<UUID, String> chatNames = chatNames(ingestedDocuments.getContent());

        return ingestedDocuments.map(ingestedDocument -> IngestedDocumentSummary.of(ingestedDocument,
                statuses.get(ingestedDocument.getId()),
                principals.getOrDefault(ingestedDocument.getId(), List.of()),
                chatNames.get(ingestedDocument.getId())));
    }

    /**
     * The conversation name for each document that came from one, in a single query.
     * <p>
     * A cross-chat listing otherwise shows a bare id and cannot label its rows, which is the one
     * thing that would make the new listing unusable in a UI.
     */
    private Map<UUID, String> chatNames(List<IngestedDocument> ingestedDocuments) {
        Map<UUID, UUID> documentChatIds = new LinkedHashMap<>();

        for (IngestedDocument ingestedDocument : ingestedDocuments) {
            Map<String, Object> metadata = ingestedDocument.getMetadata();

            if (metadata == null) {
                continue;
            }

            Object chatId = metadata.get(IngestedDocument.CHAT_ID);

            if (chatId != null) {
                documentChatIds.put(ingestedDocument.getId(), UUID.fromString(chatId.toString()));
            }
        }

        if (documentChatIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, String> namesByChatId = new LinkedHashMap<>();

        for (Chat chat : chatRepository.findAllById(documentChatIds.values())) {
            namesByChatId.put(chat.getId(), chat.getName());
        }

        Map<UUID, String> namesByDocumentId = new LinkedHashMap<>();

        documentChatIds.forEach((documentId, chatId) -> {
            String chatName = namesByChatId.get(chatId);

            if (chatName != null) {
                namesByDocumentId.put(documentId, chatName);
            }
        });

        return namesByDocumentId;
    }

    /**
     * One document as a client sees it, for a caller holding the entity already — the URI paths,
     * which return the queued row rather than re-reading it.
     * <p>
     * Public so no caller has to assemble a summary itself: the retrieve principals and the chat
     * name both come from other tables, and a caller building the record directly would be the one
     * place that could get them wrong.
     */
    public IngestedDocumentSummary summaryOf(IngestedDocument ingestedDocument) {
        return summary(ingestedDocument);
    }

    private IngestedDocumentSummary summary(IngestedDocument ingestedDocument) {
        DocumentStatus documentStatus = ingestedDocument.getDocumentStatus() != null
                ? ingestedDocument.getDocumentStatus()
                : latestStatus(ingestedDocument.getId());

        List<DocumentPrincipal> retrievePrincipals =
                documentEntitlementService.principals(ingestedDocument.getId(), GrantKind.RETRIEVE);

        return IngestedDocumentSummary.of(ingestedDocument,
                documentStatus,
                retrievePrincipals,
                chatNames(List.of(ingestedDocument)).get(ingestedDocument.getId()));
    }

    private DocumentStatus latestStatus(UUID documentId) {
        return statusHistoryRepository.findByDocumentId(documentId).stream()
                .findFirst()
                .orElse(null);
    }

    private void backfillMetadata(IngestedDocument ingestedDocument) {
        if (ingestedDocument.getDocumentSource() == null) {
            ingestedDocument.setDocumentSource(DocumentSource.USER);
        }

        if (ingestedDocument.getMetadata() == null) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(IngestedDocument.ORIGINAL_FILE_NAME, ingestedDocument.getFileName());
            metadata.put(FILE_SIZE_BYTES,
                    content(ingestedDocument.getId()).map(data -> data.length).orElse(0));
            ingestedDocument.setMetadata(metadata);
        }
    }
}
