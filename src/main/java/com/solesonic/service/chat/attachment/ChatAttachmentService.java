package com.solesonic.service.chat.attachment;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.chat.attachment.ChatAttachmentSummary;
import com.solesonic.repository.chat.ChatAttachmentRepository;
import com.solesonic.scope.UserRequestContext;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ChatAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);

    static final Set<String> ACCEPTED_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp");

    private final ChatAttachmentRepository chatAttachmentRepository;
    private final UserRequestContext userRequestContext;
    private final Duration stagedTtl;

    public ChatAttachmentService(ChatAttachmentRepository chatAttachmentRepository,
                                 UserRequestContext userRequestContext,
                                 @Value("${solesonic.llm.attachment.staged-ttl:PT24H}") Duration stagedTtl) {
        this.chatAttachmentRepository = chatAttachmentRepository;
        this.userRequestContext = userRequestContext;
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

        chatAttachmentRepository.delete(chatAttachment);
    }

    @Transactional(readOnly = true)
    public List<ChatAttachmentSummary> forChat(UUID chatId) {
        return chatAttachmentRepository.findSummariesByChatId(chatId);
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
     * Records the generated description in its own short transaction. The vision call that produced
     * it takes seconds, and must not be made inside a transaction holding a pooled connection.
     */
    @Transactional
    public void saveVisionDescription(UUID attachmentId, String visionDescription, String visionModel) {
        chatAttachmentRepository.findById(attachmentId).ifPresent(chatAttachment -> {
            chatAttachment.setVisionDescription(visionDescription);
            chatAttachment.setVisionModel(visionModel);

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
                chatAttachment.getFileSizeBytes());
    }
}
