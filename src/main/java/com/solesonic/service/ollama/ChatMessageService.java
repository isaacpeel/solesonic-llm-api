package com.solesonic.service.ollama;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.repository.UserPreferencesRepository;
import com.solesonic.repository.ollama.ChatMessageRepository;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service()
public class ChatMessageService {
    private static final Logger log =  LoggerFactory.getLogger(ChatMessageService.class);
    private final ChatMessageRepository chatMessageRepository;
    private final UserPreferencesRepository userPreferencesRepository;

    public ChatMessageService(ChatMessageRepository chatMessageRepository,
                              UserPreferencesRepository userPreferencesRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.userPreferencesRepository = userPreferencesRepository;
    }

    public void save(ChatMessage message) {
        UUID chatId = message.getChatId();

        log.info("Saving chat message with id {}", chatId);

        UserPreferences userPreferences = userPreferencesRepository
                .findByChatId(chatId)
                .orElseThrow(() -> new IllegalStateException("User preferences not found for chatId: " + chatId));

        String chatModel = userPreferences.getModel();
        message.setModel(chatModel);
        message.setTimestamp(ZonedDateTime.now());
        chatMessageRepository.save(message);
    }

    public List<Message> findByChatId(UUID chatId) {
        List<ChatMessage> chatMessages = chatMessageRepository.findByChatId(chatId);

        if(CollectionUtils.isNotEmpty(chatMessages)) {
            List<Message> messages = new ArrayList<>(chatMessages.size());

            for(ChatMessage chatMessage : chatMessages) {
                // Remove <think>...</think> tags from message
                String messageText = chatMessage.getMessage();

                Message message;
                switch (chatMessage.getMessageType()) {
                    case USER -> {
                        assert messageText != null;
                        message = new UserMessage(messageText);
                    }
                    case ASSISTANT -> {
                        assert messageText != null;
                        message = new AssistantMessage(messageText);
                    }
                    default -> {
                        assert messageText != null;
                        message = new SystemMessage(messageText);
                    }
                }

                messages.add(message);
            }

            return messages;
        }

        return List.of();
    }
}
