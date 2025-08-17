package com.solesonic.config.olllama;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.ollama.ChatMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class DatabaseChatMemory implements ChatMemory {
    private static final Logger log = LoggerFactory.getLogger(DatabaseChatMemory.class);
    private final ChatMessageService chatMessageService;
    private final UserRequestContext userRequestContext;

    public DatabaseChatMemory(ChatMessageService chatMessageService, UserRequestContext userRequestContext) {
        this.chatMessageService = chatMessageService;
        this.userRequestContext = userRequestContext;
    }

    @Override
    public void add(@NonNull String conversationId, List<Message> messages) {
        log.info("Adding messages to history.");

        UUID chatId = UUID.fromString(conversationId);
        String chatModel = userRequestContext.getChatModel();

        for(Message message : messages) {
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setChatId(chatId);
            chatMessage.setMessageType(message.getMessageType());
            chatMessage.setMessage(message.getText());
            chatMessage.setModel(chatModel);

            chatMessageService.save(chatMessage);
        }
    }

    @Override
    @NonNull
    public List<Message> get(@NonNull String conversationId) {
        log.info("Getting messages from history.");
        List<Message> messages = chatMessageService.findByChatId(UUID.fromString(conversationId));

        log.info("Messages Found: {}", messages.size());
        return messages;
    }

    @Override
    public void clear(@NonNull String conversationId) {
        //Leave as a no-op
        log.info("Clearing history.");
    }
}
