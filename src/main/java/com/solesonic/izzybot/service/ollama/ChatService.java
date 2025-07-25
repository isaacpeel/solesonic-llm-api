package com.solesonic.izzybot.service.ollama;

import com.solesonic.izzybot.model.IzzyBotResponse;
import com.solesonic.izzybot.model.chat.ChatRequest;
import com.solesonic.izzybot.model.chat.history.Chat;
import com.solesonic.izzybot.model.chat.history.ChatMessage;
import com.solesonic.izzybot.repository.ollama.ChatMessageRepository;
import com.solesonic.izzybot.repository.ollama.ChatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.ai.chat.messages.MessageType.ASSISTANT;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatRepository chatRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final PromptService promptService;

    private String removeThinkTags(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceAll("<think>.*?</think>", "");
    }

    public ChatService(
            ChatRepository chatRepository,
            ChatMessageRepository chatMessageRepository,
            PromptService promptService) {
        this.chatRepository = chatRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.promptService = promptService;
    }

    public Chat save(Chat chat) {
        chat.setTimestamp(ZonedDateTime.now());
        return chatRepository.save(chat);
    }

    public List<Chat> getByUserId(UUID userId) {
        List<Chat> chats = chatRepository.findByUserId(userId);

        for (Chat chat : chats) {
            List<ChatMessage> chatMessages = chatMessageRepository.findByChatId(chat.getId());

            // Remove <think>...</think> tags from each message
            for (ChatMessage chatMessage : chatMessages) {
                String message = chatMessage.getMessage();
                if (message != null) {
                    chatMessage.setMessage(removeThinkTags(message));
                }
            }

            chat.setChatMessages(chatMessages);
        }

        return chats;
    }

    public Chat get(UUID chatId) {
        Chat chat = chatRepository.findById(chatId).orElse(null);

        if (chat == null) {
            return null;
        }

        List<ChatMessage> chatMessages = chatMessageRepository.findByChatId(chatId);

        // Remove <think>...</think> tags from each message
        for (ChatMessage chatMessage : chatMessages) {
            String message = chatMessage.getMessage();
            if (message != null) {
                chatMessage.setMessage(removeThinkTags(message));
            }
        }

        chat.setChatMessages(chatMessages);

        return chat;
    }

    public IzzyBotResponse create(UUID userId, ChatRequest chatRequest) {
        Chat chat = new Chat();
        chat.setUserId(userId);

        chat = save(chat);

        UUID chatId = chat.getId();
        log.info("Starting standard chat with new chat id {}", chatId);

        return update(chatId, chatRequest);
    }

    public IzzyBotResponse update(UUID chatId, ChatRequest chatRequest) {
        String chatMessage = chatRequest.chatMessage();

        // Build prompt using PromptService
        Prompt contextPrompt = promptService.buildTemplatePrompt(chatMessage);

        // Get response from PromptService
        String responseContent = promptService.prompt(chatId, chatMessage, contextPrompt);

        // Get model from PromptService
        String chatModel = promptService.getModel();

        // Create response message
        ChatMessage responseMessage = new ChatMessage();
        responseMessage.setChatId(chatId);
        responseMessage.setMessageType(ASSISTANT);
        responseMessage.setMessage(removeThinkTags(responseContent));
        responseMessage.setModel(chatModel);

        return new IzzyBotResponse(chatId, responseMessage);
    }
}
