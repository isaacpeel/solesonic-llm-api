package com.solesonic.service.chat.attachment;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.chat.attachment.ChatAttachmentSummary;
import com.solesonic.model.chat.attachment.ExtractionFailureReason;
import com.solesonic.model.chat.attachment.VisionFailureReason;
import com.solesonic.repository.chat.ChatAttachmentRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.ingestion.IngestedDocumentService;
import com.solesonic.service.rag.VectorStoreService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ChatAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);

    /**
     * Images are described by the vision model and reach the chat model as prose.
     */
    static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp");

    /**
     * Documents are extracted to text, split, embedded, and reached through retrieval instead.
     * Every type here is one the readers already wired into {@code DocumentService} can parse — the
     * PDF reader, or Tika for the rest — so widening this set is most of what it takes to accept a
     * new format.
     */
    static final Set<String> DOCUMENT_CONTENT_TYPES = Set.of(
            "application/pdf",
            "text/plain",
            "text/markdown",
            "text/html",
            "text/csv",
            "text/xml",
            "application/xml",
            "application/json",
            "application/rtf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet");

    static final Set<String> ACCEPTED_CONTENT_TYPES = acceptedContentTypes();

    private static Set<String> acceptedContentTypes() {
        Set<String> accepted = new HashSet<>(IMAGE_CONTENT_TYPES);
        accepted.addAll(DOCUMENT_CONTENT_TYPES);

        return Set.copyOf(accepted);
    }

    /**
     * Which of the two passes an attachment belongs to. Decided on the stored content type rather
     * than the file name, which is client-supplied and proves nothing.
     */
    public static boolean isImage(String contentType) {
        return contentType != null && IMAGE_CONTENT_TYPES.contains(contentType.toLowerCase());
    }

    private final ChatAttachmentRepository chatAttachmentRepository;
    private final UserRequestContext userRequestContext;
    private final VectorStoreService vectorStoreService;
    private final IngestedDocumentService ingestedDocumentService;
    private final Duration stagedTtl;

    public ChatAttachmentService(ChatAttachmentRepository chatAttachmentRepository,
                                 UserRequestContext userRequestContext,
                                 VectorStoreService vectorStoreService,
                                 IngestedDocumentService ingestedDocumentService,
                                 @Value("${solesonic.llm.attachment.staged-ttl:PT24H}") Duration stagedTtl) {
        this.chatAttachmentRepository = chatAttachmentRepository;
        this.userRequestContext = userRequestContext;
        this.vectorStoreService = vectorStoreService;
        this.ingestedDocumentService = ingestedDocumentService;
        this.stagedTtl = stagedTtl;
    }

    @Transactional
    public ChatAttachmentSummary stage(MultipartFile file, String description) {
        String contentType = file.getContentType();

        if (contentType == null || !ACCEPTED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported attachment content type: " + contentType);
        }

        UUID userId = userRequestContext.getUserId();

        log.info("Staging attachment for user {}", userId);

        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setUserId(userId);
        chatAttachment.setFileName(file.getOriginalFilename());
        chatAttachment.setDescription(StringUtils.trimToNull(description));
        chatAttachment.setContentType(contentType.toLowerCase());
        chatAttachment.setFileData(fileData(file));
        chatAttachment.setFileSizeBytes(file.getSize());
        chatAttachment.setCreated(ZonedDateTime.now());

        ChatAttachment staged = chatAttachmentRepository.save(chatAttachment);

        return summary(staged);
    }

    @Transactional(readOnly = true)
    public ChatAttachment get(UUID attachmentId) {
        UUID userId = userRequestContext.getUserId();

        return chatAttachmentRepository.findByIdAndUserId(attachmentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Attachment not found: " + attachmentId));
    }

    @Transactional
    public void delete(UUID attachmentId) {
        ChatAttachment chatAttachment = get(attachmentId);

        log.info("Deleting attachment {}", attachmentId);

        //An indexed document also has an ingested_document row, which nothing lists and nothing
        //else would ever clean up. Asked for unconditionally: a row left FAILED or mid-ingest has
        //no chunk count to test against, and it is exactly the row that would be left behind.
        ingestedDocumentService.deleteByChatAttachmentId(attachmentId);

        //A document's chunks live in the vector store, which has no foreign key to cascade from.
        //Left behind they would keep answering questions about a document the user just removed.
        //Still its own sweep after the row: it also reaches chunks with no row behind them.
        vectorStoreService.deleteByChatAttachmentId(attachmentId);

        chatAttachmentRepository.delete(chatAttachment);
    }

    @Transactional(readOnly = true)
    public List<ChatAttachmentSummary> forChat(UUID chatId) {
        return chatAttachmentRepository.findSummariesByChatId(chatId);
    }

    /**
     * Discards every attachment of a conversation that is being deleted.
     * <p>
     * Not user-scoped, unlike {@link #delete(UUID)}: the caller has already established that the
     * conversation is theirs, and an attachment reaches a chat only by being bound to a message of
     * it. It joins the caller's transaction so that a chat, its images, and the
     * {@code ingested_document} rows its documents opened go together or not at all.
     */
    @Transactional
    public void deleteForChat(UUID chatId) {
        ingestedDocumentService.deleteByChatId(chatId);

        vectorStoreService.deleteByChatId(chatId);

        int deleted = chatAttachmentRepository.deleteByChatId(chatId);

        if (deleted > 0) {
            log.info("Deleted {} attachment(s) of chat {}", deleted, chatId);
        }
    }

    /**
     * Loads the attachments named by one send, image bytes included, so they can be described.
     * <p>
     * {@code userId} is a parameter rather than read from {@link UserRequestContext} for the same
     * reason it is on {@link #bind}: this runs on a {@code boundedElastic} thread with no request
     * scope bound.
     */
    @Transactional(readOnly = true)
    public List<ChatAttachment> attachments(UUID userId, Set<UUID> attachmentIds) {
        if (CollectionUtils.isEmpty(attachmentIds)) {
            return List.of();
        }

        return chatAttachmentRepository.findByIdInAndUserIdOrderByCreatedAsc(attachmentIds, userId);
    }

    /**
     * Splits the ids named by one send into the two passes that handle them: images are described by
     * the vision model, everything else is extracted and indexed for retrieval.
     * <p>
     * Each pass guarantees exactly one {@code attachment} SSE event per id it is given, so the two
     * sets must be disjoint and must together cover every requested id — a client cannot tell a
     * missing event from a failure.
     * <p>
     * An id that resolves to no row lands in {@code imageIds}. It has to land somewhere to be
     * signalled at all, and that is where an unresolvable id was already reported from before
     * documents existed.
     * <p>
     * Not {@code @Transactional}: it is a single read, and it is called from {@code PromptService}
     * on the reactive path, where holding a transaction open buys nothing and costs a pooled
     * connection.
     */
    public AttachmentPartition partition(UUID userId, Set<UUID> attachmentIds) {
        if (CollectionUtils.isEmpty(attachmentIds)) {
            return new AttachmentPartition(Set.of(), Set.of());
        }

        //Starting from every requested id, rather than from the rows, is what puts an id that
        //resolved to nothing on the image side without needing a separate pass to find them.
        Set<UUID> imageIds = new LinkedHashSet<>(attachmentIds);
        Set<UUID> documentIds = new LinkedHashSet<>();

        for (ChatAttachment attachment : chatAttachmentRepository
                .findByIdInAndUserIdOrderByCreatedAsc(attachmentIds, userId)) {
            if (isImage(attachment.getContentType())) {
                continue;
            }

            imageIds.remove(attachment.getId());
            documentIds.add(attachment.getId());
        }

        return new AttachmentPartition(imageIds, documentIds);
    }

    /**
     * @param imageIds    ids to describe with the vision model, plus any id that resolved to no row
     * @param documentIds ids to extract and index for retrieval
     */
    public record AttachmentPartition(Set<UUID> imageIds, Set<UUID> documentIds) {
    }

    /**
     * Records the generated description in its own short transaction. The vision call that produced
     * it takes seconds, and must not be made inside a transaction holding a pooled connection.
     */
    @Transactional
    public void saveVisionDescription(UUID attachmentId, String visionDescription, String visionModel) {
        chatAttachmentRepository.findById(attachmentId).ifPresent(chatAttachment -> {
            chatAttachment.setVisionDescription(visionDescription);
            chatAttachment.setVisionModel(visionModel);
            chatAttachment.setVisionFailureReason(null);

            chatAttachmentRepository.save(chatAttachment);
        });
    }

    /**
     * Records why an image was left undescribed, so history can explain itself long after the SSE
     * frame that carried the same reason is gone.
     * <p>
     * The description stays null, which is what keeps the work retryable: a later turn naming the
     * same attachment tries again rather than trusting this row.
     */
    @Transactional
    public void saveVisionFailure(UUID attachmentId, VisionFailureReason visionFailureReason) {
        chatAttachmentRepository.findById(attachmentId).ifPresent(chatAttachment -> {
            chatAttachment.setVisionFailureReason(visionFailureReason);

            chatAttachmentRepository.save(chatAttachment);
        });
    }

    /**
     * Records how many chunks a document was indexed as, in its own short transaction for the same
     * reason {@link #saveVisionDescription} is: the extraction and embedding that produced them run
     * for seconds and must not hold a pooled connection.
     */
    @Transactional
    public void saveChunkCount(UUID attachmentId, int chunkCount) {
        chatAttachmentRepository.findById(attachmentId).ifPresent(chatAttachment -> {
            chatAttachment.setChunkCount(chunkCount);
            chatAttachment.setExtractionFailureReason(null);

            chatAttachmentRepository.save(chatAttachment);
        });
    }

    /**
     * Records why a document was left unindexed. {@code chunkCount} stays null, which is what keeps
     * the work retryable: a later turn naming the same attachment tries again rather than trusting
     * this row.
     */
    @Transactional
    public void saveExtractionFailure(UUID attachmentId, ExtractionFailureReason extractionFailureReason) {
        chatAttachmentRepository.findById(attachmentId).ifPresent(chatAttachment -> {
            chatAttachment.setExtractionFailureReason(extractionFailureReason);

            chatAttachmentRepository.save(chatAttachment);
        });
    }

    /**
     * Descriptions of every described attachment bound to a chat, for replaying image context into
     * earlier turns of the conversation.
     */
    @Transactional(readOnly = true)
    public List<ChatAttachmentDescription> descriptions(UUID chatId) {
        return chatAttachmentRepository.findDescriptionsByChatId(chatId);
    }

    /**
     * Claims the given staged attachments for a chat message. The conditional update is the
     * authoritative check: an id that does not exist, belongs to another user, or is already
     * bound simply will not match, and the short row count fails the whole turn.
     * <p>
     * {@code userId} is a parameter rather than read from {@link UserRequestContext} because
     * this runs on a {@code boundedElastic} thread where the request scope is not bound.
     */
    public void bind(UUID userId, UUID chatId, UUID chatMessageId, Set<UUID> attachmentIds) {
        if (CollectionUtils.isEmpty(attachmentIds)) {
            return;
        }

        log.info("Binding {} attachment(s) to chat message {}", attachmentIds.size(), chatMessageId);

        int bound = chatAttachmentRepository.bind(attachmentIds, userId, chatId, chatMessageId);

        if (bound != attachmentIds.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Attachments could not be bound, expected " + attachmentIds.size() + " but claimed " + bound);
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    @Transactional
    public int sweepStaged() {
        ZonedDateTime cutoff = ZonedDateTime.now().minus(stagedTtl);

        int deleted = chatAttachmentRepository.deleteStagedOlderThan(cutoff);

        if (deleted > 0) {
            log.info("Swept {} staged attachment(s) older than {}", deleted, cutoff);
        }

        return deleted;
    }

    private byte[] fileData(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ioException) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read uploaded attachment", ioException);
        }
    }

    private ChatAttachmentSummary summary(ChatAttachment chatAttachment) {
        return new ChatAttachmentSummary(
                chatAttachment.getId(),
                chatAttachment.getChatMessageId(),
                chatAttachment.getFileName(),
                chatAttachment.getDescription(),
                chatAttachment.getContentType(),
                chatAttachment.getFileSizeBytes(),
                chatAttachment.getVisionDescription() != null,
                chatAttachment.getVisionFailureReason(),
                chatAttachment.getChunkCount() != null,
                chatAttachment.getExtractionFailureReason());
    }
}
