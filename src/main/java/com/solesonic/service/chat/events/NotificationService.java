package com.solesonic.service.chat.events;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.service.ollama.ChatMessageService;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    public static final String CHAT_ID = "chatId";
    public static final String PROGRESS = "progress";

    private static final String EVENTS_CHANNEL_PREFIX = "elicitation:events:";
    public static final String EVENT = "event";
    public static final String DATA = "data";

    private final JsonMapper jsonMapper;
    private final ChatMessageService chatMessageService;
    private final ReactiveStringRedisTemplate redisTemplate;

    public NotificationService(JsonMapper jsonMapper,
                               ChatMessageService chatMessageService,
                               ReactiveStringRedisTemplate redisTemplate) {
        this.jsonMapper = jsonMapper;
        this.chatMessageService = chatMessageService;
        this.redisTemplate = redisTemplate;
    }

    public void emitProgress(UUID chatId, NotificationEventMessage notificationEventMessage) {
        Map<String, Object> progressJson = jsonMapper.convertValue(notificationEventMessage, new TypeReference<>() {
        });

        progressJson.put(CHAT_ID, chatId.toString());

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setChatId(chatId);
        chatMessage.setMessageType(MessageType.SYSTEM);
        chatMessage.setMessage(notificationEventMessage.message());
        chatMessage.setProgressData(progressJson);
        chatMessageService.save(chatMessage);

        String message = serializeEventMessage(progressJson);

        redisTemplate.convertAndSend(eventsChannelKey(chatId), message)
                .subscribe(subscriberCount -> log.debug("Emitted progress event to {} subscribers for chat {}", subscriberCount, chatId));
    }
    public void emitProgress(UUID chatId, Message a2aMessage) {
        log.info("Emitting a2a notification.");

        List<Part<?>> parts = a2aMessage.getParts();

        String messageText = parts.stream()
                .<String>mapMulti((part, downstream) -> {
                    if (part instanceof TextPart textPart) {
                        downstream.accept(textPart.getText());
                    }
                })
                .collect(Collectors.joining());

        log.info("Emitting a2a progress for chat id {} with message: {}", chatId, messageText);

        String progressToken = a2aMessage.getMessageId();

        NotificationEventMessage notificationEventMessage = new NotificationEventMessage(progressToken, messageText, null, null);
        emitProgress(chatId, notificationEventMessage);
    }

    public void emitProgress(UUID chatId, McpSchema.ProgressNotification progressNotification) {
        log.info("Emitting mcp progress for chat id {} with message: {}", chatId, progressNotification.message());

        String message = progressNotification.message();
        String progress = progressNotification.progress().toString();
        String total = progressNotification.total().toString();
        String progressToken = progressNotification.progressToken().toString();

        NotificationEventMessage notificationEventMessage = new NotificationEventMessage(progressToken, message, progress, total);
        emitProgress(chatId, notificationEventMessage);
    }

    private String serializeEventMessage(Object data) {
        return jsonMapper.writeValueAsString(Map.of(EVENT, NotificationService.PROGRESS, DATA, data));
    }

    private static String eventsChannelKey(UUID chatId) {
        return EVENTS_CHANNEL_PREFIX + chatId;
    }
}
