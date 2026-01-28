package com.solesonic.config.olllama;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.service.ollama.ChatMessageService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class DatabaseChatMemory implements ChatMemory {
    private static final Logger log = LoggerFactory.getLogger(DatabaseChatMemory.class);
    private final ChatMessageService chatMessageService;


    public DatabaseChatMemory(ChatMessageService chatMessageService) {
        this.chatMessageService = chatMessageService;
    }

    private String sanitize(String text) {
        if (text == null) {
            return null;
        }

        String trimmed = text.trim();

        if (trimmed.isEmpty()) {
            return null;
        }

        return trimmed;
    }

    @Override
    public void add(@NonNull String conversationId, List<Message> messages) {
        log.debug("Adding messages to history.");

        UUID chatId = UUID.fromString(conversationId);

        for (Message message : messages) {
            String sanitizedText = sanitize(message.getText());

            if (sanitizedText == null) {
                continue;
            }

            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setChatId(chatId);
            chatMessage.setMessageType(message.getMessageType());
            chatMessage.setMessage(sanitizedText);

            chatMessageService.save(chatMessage);
        }
    }

    @Override
    @NullMarked
    public List<Message> get(String conversationId) {
        log.debug("Getting messages from history.");
        List<Message> messages = chatMessageService.findByChatId(UUID.fromString(conversationId));

        log.debug("Messages Found: {}", messages.size());
        return messages;
    }

    @Override
    @NullMarked
    public void clear(String conversationId) {
        //Leave as a no-op
        log.info("Clearing history.");
    }
}
