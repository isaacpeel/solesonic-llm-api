package com.solesonic.config.olllama;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.scope.StreamUserRequestContext;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.ollama.ChatMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.UUID;

@Component
public class DatabaseChatMemory implements ChatMemory {
    private static final Logger log = LoggerFactory.getLogger(DatabaseChatMemory.class);
    private final ChatMessageService chatMessageService;
    private final UserRequestContext userRequestContext;


    public DatabaseChatMemory(ChatMessageService chatMessageService,
                              UserRequestContext userRequestContext) {
        this.chatMessageService = chatMessageService;
        this.userRequestContext = userRequestContext;
    }

    private String sanitize(String text) {
        if (text == null) {
            return null;
        }

        String withoutThink = text.replaceAll("<think>.*?</think>", "");
        String trimmed = withoutThink.trim();

        if (trimmed.isEmpty()) {
            return null;
        }

        return trimmed;
    }

    private boolean isRequestScopeActive() {
        return RequestContextHolder.getRequestAttributes() != null;
    }

    @Override
    public void add(@NonNull String conversationId, @NonNull List<Message> messages) {
        log.debug("Adding messages to history.");

        UUID chatId = UUID.fromString(conversationId);

        String chatModel;

        if (isRequestScopeActive()) {
            chatModel = userRequestContext.getChatModel();
            log.info("Using chat model from request context: {}", chatModel);
        } else {
            chatModel = StreamUserRequestContext.getChatModel();
            log.info("Using chat model from StreamContextHolder: {}", chatModel);
        }

        for (Message message : messages) {
            String sanitizedText = sanitize(message.getText());

            if (sanitizedText == null) {
                continue;
            }

            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setChatId(chatId);
            chatMessage.setMessageType(message.getMessageType());
            chatMessage.setMessage(sanitizedText);
            chatMessage.setModel(chatModel);

            chatMessageService.save(chatMessage);
        }
    }

    @Override
    @NonNull
    public List<Message> get(@NonNull String conversationId) {
        log.debug("Getting messages from history.");
        List<Message> messages = chatMessageService.findByChatId(UUID.fromString(conversationId));

        log.debug("Messages Found: {}", messages.size());
        return messages;
    }

    @Override
    public void clear(@NonNull String conversationId) {
        //Leave as a no-op
        log.info("Clearing history.");
    }
}
