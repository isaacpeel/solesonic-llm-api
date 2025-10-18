package com.solesonic.service.ollama;

import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.repository.ollama.ChatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.ZonedDateTime;
import java.util.UUID;

@Service
public class StreamingChatService {
    private static final Logger log = LoggerFactory.getLogger(StreamingChatService.class);

    private final ChatRepository chatRepository;
    private final PromptService promptService;

    public StreamingChatService(ChatRepository chatRepository,
                                PromptService promptService) {
        this.chatRepository = chatRepository;
        this.promptService = promptService;
    }

    private String removeThinkTags(String message) {
        if (message == null) {
            return null;
        }

        return message.replaceAll("<think>.*?</think>", "");
    }

    private Chat save(Chat chat) {
        chat.setTimestamp(ZonedDateTime.now());
        return chatRepository.save(chat);
    }

    public Flux<String> create(UUID userId, ChatRequest chatRequest) {
        Chat chat = new Chat();
        chat.setUserId(userId);

        chat = save(chat);

        UUID chatId = chat.getId();

        log.info("Starting streaming chat with new chat id {}", chatId);

        return update(chatId, chatRequest);
    }

    public Flux<String> update(UUID chatId, ChatRequest chatRequest) {
        String chatMessage = chatRequest.chatMessage();

        return promptService.stream(chatId, chatMessage)
                .map(this::removeThinkTags)
                .filter(chunk -> chunk != null && !chunk.isEmpty());
    }
}
