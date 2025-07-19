package com.solesonic.izzybot.service.ollama;

import com.solesonic.izzybot.model.chat.history.ChatMessage;
import com.solesonic.izzybot.repository.ollama.ChatMessageRepository;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service()
public class ChatMessageService {
    private final ChatMessageRepository chatMessageRepository;

    public ChatMessageService(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    public void save(ChatMessage message) {
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
