package com.solesonic.service.ollama;

import com.solesonic.model.chat.attachment.ChatAttachmentSummary;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.repository.ollama.ChatMessageRepository;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.repository.ollama.ChatRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.image.GeneratedImageService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final int MAX_NAME_LENGTH = 255;

    private final ChatRepository chatRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatAttachmentService chatAttachmentService;
    private final GeneratedImageService generatedImageService;
    private final UserRequestContext userRequestContext;

    private String removeThinkTags(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceAll("<think>.*?</think>", "");
    }

    public ChatService(
            ChatRepository chatRepository,
            ChatMessageRepository chatMessageRepository,
            ChatAttachmentService chatAttachmentService,
            GeneratedImageService generatedImageService,
            UserRequestContext userRequestContext) {
        this.chatRepository = chatRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.chatAttachmentService = chatAttachmentService;
        this.generatedImageService = generatedImageService;
        this.userRequestContext = userRequestContext;
    }

    public Page<Chat> getByUserId(UUID userId, Pageable pageable) {
        log.info("Getting chats by user id {} page {} size {}", userId, pageable.getPageNumber(), pageable.getPageSize());
        Page<Chat> chats = chatRepository.findByUserId(userId, pageable);

        for (Chat chat : chats) {
            chat.setChatMessages(chatMessages(chat.getId()));
        }

        return chats;
    }

    public Chat get(UUID chatId) {
        Chat chat = chatRepository.findById(chatId).orElse(null);

        if (chat == null) {
            return null;
        }

        chat.setChatMessages(chatMessages(chatId));

        return chat;
    }

    /**
     * Sets a chat's display name, scoped to the caller: {@link UserRequestContext#getUserId()}
     * comes only from the JWT subject, never from a client-supplied path segment, so a chat owned
     * by someone else is indistinguishable from one that does not exist.
     */
    public Chat rename(UUID chatId, String name) {
        if (StringUtils.isBlank(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chat name must not be blank");
        }

        String trimmedName = name.trim();

        if (trimmedName.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Chat name must be " + MAX_NAME_LENGTH + " characters or fewer");
        }

        UUID userId = userRequestContext.getUserId();

        Chat chat = chatRepository.findByIdAndUserId(chatId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found: " + chatId));

        chat.setName(trimmedName);

        log.info("Renaming chat {} for user {}", chatId, userId);

        return chatRepository.save(chat);
    }

    private List<ChatMessage> chatMessages(UUID chatId) {
        List<ChatMessage> chatMessages = chatMessageRepository.findByChatId(chatId);

        // One attachment query per chat, not per message.
        Map<UUID, List<ChatAttachmentSummary>> attachmentsByMessageId = chatAttachmentService.forChat(chatId)
                .stream()
                .collect(Collectors.groupingBy(ChatAttachmentSummary::chatMessageId));

        // Likewise one image query per chat. References only — the bytes stay in the table and are
        // fetched per image from the download endpoint, which is what keeps a conversation with a
        // dozen images a few kilobytes of JSON rather than tens of megabytes.
        Map<UUID, List<GeneratedImageSummary>> generatedImagesByMessageId = generatedImageService.forChat(chatId)
                .stream()
                .collect(Collectors.groupingBy(GeneratedImageSummary::chatMessageId));

        for (ChatMessage chatMessage : chatMessages) {
            // Remove <think>...</think> tags from each message
            String message = chatMessage.getMessage();
            if (message != null) {
                chatMessage.setMessage(removeThinkTags(message));
            }

            chatMessage.setAttachments(attachmentsByMessageId.getOrDefault(chatMessage.getId(), List.of()));
            chatMessage.setGeneratedImages(
                    generatedImagesByMessageId.getOrDefault(chatMessage.getId(), List.of()));
        }

        return chatMessages;
    }
}
