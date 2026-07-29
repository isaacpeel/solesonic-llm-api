package com.solesonic.service.ollama;

import com.solesonic.model.chat.attachment.ChatAttachmentSummary;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.repository.ollama.ChatMessageRepository;
import com.solesonic.repository.ollama.ChatRepository;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatRepository chatRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatAttachmentService chatAttachmentService;

    private String removeThinkTags(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceAll("<think>.*?</think>", "");
    }

    public ChatService(
            ChatRepository chatRepository,
            ChatMessageRepository chatMessageRepository,
            ChatAttachmentService chatAttachmentService) {
        this.chatRepository = chatRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.chatAttachmentService = chatAttachmentService;
    }

    public List<Chat> getByUserId(UUID userId) {
        log.info("Getting chats by user id {}", userId);
        List<Chat> chats = chatRepository.findByUserId(userId);

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

    private List<ChatMessage> chatMessages(UUID chatId) {
        List<ChatMessage> chatMessages = chatMessageRepository.findByChatId(chatId);

        // One attachment query per chat, not per message.
        Map<UUID, List<ChatAttachmentSummary>> attachmentsByMessageId = chatAttachmentService.forChat(chatId)
                .stream()
                .collect(Collectors.groupingBy(ChatAttachmentSummary::chatMessageId));

        for (ChatMessage chatMessage : chatMessages) {
            // Remove <think>...</think> tags from each message
            String message = chatMessage.getMessage();
            if (message != null) {
                chatMessage.setMessage(removeThinkTags(message));
            }

            chatMessage.setAttachments(attachmentsByMessageId.getOrDefault(chatMessage.getId(), List.of()));
        }

        return chatMessages;
    }
}
