package com.solesonic.service.chat.events;

import com.solesonic.model.chat.attachment.ChatAttachmentEvent;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.service.ollama.ChatMessageService;
import io.modelcontextprotocol.spec.McpSchema;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    public static final String CHAT_ID = "chatId";
    public static final String PROGRESS = "progress";
    public static final String ATTACHMENT = "attachment";
    public static final String IMAGE = "image";

    private static final String EVENTS_CHANNEL_PREFIX = "elicitation:events:";
    public static final String EVENT = "event";
    public static final String DATA = "data";
    public static final String ERROR = "error";

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

        String message = serializeEventMessage(PROGRESS, progressJson);

        redisTemplate.convertAndSend(eventsChannelKey(chatId), message)
                .subscribe(subscriberCount -> log.debug("Emitted progress event to {} subscribers for chat {}", subscriberCount, chatId));
    }
    /**
     * Closes the vision pass for one attachment, on a distinct event name so the frontend routes it
     * as state rather than as another step of user-facing progress text.
     * <p>
     * Unlike {@link #emitProgress}, this writes no {@code SYSTEM} chat message: the durable half of
     * the signal is {@code chat_attachment.vision_description} — read back through
     * {@code ChatAttachmentSummary.described} — so persisting a row per image here would only grow
     * history with something the client already filters out.
     */
    public void emitAttachment(UUID chatId, ChatAttachmentEvent chatAttachmentEvent) {
        log.debug("Emitting attachment event for chat {}: {}", chatId, chatAttachmentEvent);

        String message = serializeEventMessage(ATTACHMENT, chatAttachmentEvent);

        redisTemplate.convertAndSend(eventsChannelKey(chatId), message)
                .subscribe(subscriberCount ->
                        log.debug("Emitted attachment event to {} subscribers for chat {}", subscriberCount, chatId));
    }

    /**
     * Announces an image generated during this turn, on its own event name so the frontend renders
     * it rather than routing it through progress and printing the JSON as step text.
     * <p>
     * Emitted from the tool result, which lands before the model has written a word — so it always
     * reaches the client ahead of the {@code done} frame that finalises the assistant bubble.
     * <p>
     * Like {@link #emitAttachment} this writes no {@code SYSTEM} chat message: the durable half of
     * the signal is the {@code generated_image} row, replayed onto the assistant message by
     * {@code ChatService}.
     */
    public void emitGeneratedImage(UUID chatId, GeneratedImageSummary generatedImageSummary) {
        log.info("Emitting generated image {} for chat {}", generatedImageSummary.imageId(), chatId);

        String message = serializeEventMessage(IMAGE, generatedImageSummary);

        redisTemplate.convertAndSend(eventsChannelKey(chatId), message)
                .subscribe(subscriberCount ->
                        log.debug("Emitted image event to {} subscribers for chat {}", subscriberCount, chatId));
    }

    public void emitProgress(UUID chatId, Message a2aMessage) {
        log.debug("Emitting a2a notification.");

        String messageText = a2aMessage.parts().stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).text())
                .collect(Collectors.joining());

        log.debug("Emitted a2a progress for chat id {} with message: {}", chatId, messageText);

        String progressToken = a2aMessage.messageId();

        NotificationEventMessage notificationEventMessage = new NotificationEventMessage(progressToken, messageText, null, null);
        emitProgress(chatId, notificationEventMessage);
    }

    public void emitProgress(UUID chatId, McpSchema.ProgressNotification progressNotification) {
        log.info("Emitting mcp progress for chat id {} with message: {}", chatId, progressNotification.message());

        String message = progressNotification.message();
        String progress = progressNotification.progress().toString();
        String total = progressNotification.total() != null ? progressNotification.total().toString() : null;
        String progressToken = progressNotification.progressToken().toString();

        NotificationEventMessage notificationEventMessage = new NotificationEventMessage(progressToken, message, progress, total);
        emitProgress(chatId, notificationEventMessage);
    }

    public void emitFailure(UUID chatId, String message) {
        log.warn("Emitting failure notification for chat id {} with message: {}", chatId, message);

        Map<String, Object> errorData = new HashMap<>();
        errorData.put(CHAT_ID, chatId.toString());
        errorData.put("message", message);

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setChatId(chatId);
        chatMessage.setMessageType(MessageType.SYSTEM);
        chatMessage.setMessage(message);
        chatMessage.setProgressData(errorData);
        chatMessageService.save(chatMessage);

        String payload = serializeEventMessage(ERROR, errorData);
        redisTemplate.convertAndSend(eventsChannelKey(chatId), payload)
                .subscribe(subscriberCount ->
                        log.debug("Emitted error event to {} subscribers for chat {}", subscriberCount, chatId));
    }

    private String serializeEventMessage(String eventType, Object data) {
        return jsonMapper.writeValueAsString(Map.of(EVENT, eventType, DATA, data));
    }

    private static String eventsChannelKey(UUID chatId) {
        return EVENTS_CHANNEL_PREFIX + chatId;
    }
}
