package com.solesonic.service.chat;

import com.solesonic.mcp.client.elicitation.ElicitationProvider;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.service.ollama.ChatMessageService;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static io.modelcontextprotocol.spec.McpSchema.ElicitResult.Action.*;

@Service
public class ElicitationService {
    private static final Logger log = LoggerFactory.getLogger(ElicitationService.class);
    public static final String ELICITATION_ID = "elicitationId";
    public static final String CHAT_ID = "chatId";
    public static final String ELICITATION = "elicitation";
    public static final String PROGRESS = "progress";
    public static final String CANCEL_ACTION = "cancel";

    private static final String CLOSE_EVENT = "__close__";
    private static final String EVENTS_CHANNEL_PREFIX = "elicitation:events:";
    private static final String FIELDS_KEY_PREFIX = "elicitation:fields:";

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    public record ElicitationHandle(UUID elicitationId) {}

    private final JsonMapper jsonMapper;
    private final ChatMessageService chatMessageService;
    private final ReactiveStringRedisTemplate redisTemplate;

    @Value("${solesonic.elicitation.timeout-seconds:600}")
    private long timeoutSeconds;

    public ElicitationService(JsonMapper jsonMapper,
                              ChatMessageService chatMessageService,
                              ReactiveStringRedisTemplate redisTemplate) {
        this.jsonMapper = jsonMapper;
        this.chatMessageService = chatMessageService;
        this.redisTemplate = redisTemplate;
    }

    @SuppressWarnings("unchecked")
    public Flux<ServerSentEvent<?>> registerChat(UUID chatId) {
        return (Flux<ServerSentEvent<?>>) (Flux<?>) redisTemplate.listenToChannel(eventsChannelKey(chatId))
                .map(message -> deserializeEventMessage(message.getMessage()))
                .takeWhile(serverSentEvent -> !CLOSE_EVENT.equals(serverSentEvent.event()))
                .share();
    }

    public void closeChat(UUID chatId) {
        String closeMessage = serializeEventMessage(CLOSE_EVENT, "");

        redisTemplate.convertAndSend(eventsChannelKey(chatId), closeMessage)
                .subscribe(count -> log.debug("Closed elicitation channel for chat {}", count));
    }

    public ElicitationHandle prepareElicitation() {
        return new ElicitationHandle(UUID.randomUUID());
    }

    public void emitElicitation(UUID chatId, UUID elicitationId, McpSchema.ElicitRequest request) {
        log.debug("Emitting elicitation for chat id {}", chatId);

        try {
            Map<String, Object> requestJson = jsonMapper.convertValue(request, new TypeReference<>() {});
            requestJson.put(ELICITATION_ID, elicitationId.toString());
            requestJson.put(CHAT_ID, chatId.toString());

            log.info("Emitting elicitation event for chat {}", chatId);

            String elicitationMessage = request.message();
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setMessage(elicitationMessage);
            chatMessage.setChatId(chatId);
            chatMessage.setMessageType(MessageType.SYSTEM);
            chatMessageService.save(chatMessage);

            String message = serializeEventMessage(ELICITATION, requestJson);

            redisTemplate.convertAndSend(eventsChannelKey(chatId), message)
                    .subscribe(subscriberCount -> log.info("Emitted elicitation event to {} subscribers for chat {}", subscriberCount, chatId));
        } catch (IllegalArgumentException illegalArgumentException) {
            log.error("Failed to serialize elicitation request for chat {}", chatId, illegalArgumentException);
        }

        log.info("Finished emitting elicitation event for chat {}", chatId);
    }

    public void emitProgress(UUID chatId, McpSchema.ProgressNotification progressNotification) {
        log.info("Emitting progress for chat id {} with message: {}", chatId, progressNotification.message());

        try {
            Map<String, Object> progressJson = jsonMapper.convertValue(progressNotification, new TypeReference<>() {});
            progressJson.put(CHAT_ID, chatId.toString());

            String message = serializeEventMessage(PROGRESS, progressJson);

            redisTemplate.convertAndSend(eventsChannelKey(chatId), message)
                    .subscribe(subscriberCount -> log.debug("Emitted progress event to {} subscribers for chat {}", subscriberCount, chatId));
        } catch (IllegalArgumentException illegalArgumentException) {
            log.info("Failed to serialize progress notification for chat {}", chatId, illegalArgumentException);
        }
    }

    public Mono<Boolean> completeFromFrontend(ElicitationProvider.DeleteConfirmation deleteConfirmation) {
        log.info("Completing elicitation for chat {}", deleteConfirmation.chatId());

        McpSchema.ElicitResult.Action action = deleteConfirmation.action();
        if (action == null) {
            log.warn("Missing action in elicitation response for chat {}", deleteConfirmation.chatId());
            return Mono.just(false);
        }

        log.info("Elicitation action: {}", action);

        UUID chatId = deleteConfirmation.chatId();
        UUID elicitationId = deleteConfirmation.elicitationId();

        log.info("Response for chat id: {}", chatId);
        log.info("Response for elicitationId: {}", elicitationId);

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setMessage(action.name().toLowerCase());
        chatMessage.setChatId(chatId);
        chatMessage.setMessageType(MessageType.USER);
        chatMessageService.save(chatMessage);

        String contentJson = jsonMapper.writeValueAsString(deleteConfirmation);

        Mono<Boolean> storeContent = redisTemplate.opsForValue()
                .set(fieldsKey(chatId, elicitationId), contentJson, Duration.ofSeconds(timeoutSeconds + 60));

        Mono<Long> publishCancelEvent = Mono.just(0L);

        if (action == CANCEL) {
            log.info("Emitting cancel event for chat {}", deleteConfirmation.chatId());
            publishCancelEvent = redisTemplate.convertAndSend(
                    eventsChannelKey(chatId), serializeEventMessage(CANCEL_ACTION, CANCEL_ACTION));
        }

        return storeContent
                .then(publishCancelEvent)
                .thenReturn(true);
    }

    public Mono<StructuredElicitResult<ElicitationProvider.DeleteConfirmation>> awaitResultAsync(UUID chatId, UUID elicitationId) {
        String resultFieldsKey = fieldsKey(chatId, elicitationId);
        Duration timeout = Duration.ofSeconds(timeoutSeconds);

        return Mono.defer(() -> readStoredResult(resultFieldsKey))
                .repeatWhenEmpty(repeat -> repeat.delayElements(POLL_INTERVAL))
                .timeout(timeout)
                .doOnNext(result -> log.info("Elicitation result received for chat {} id {}: {}",
                        chatId,
                        elicitationId,
                        result.action()))
                .onErrorResume(throwable -> {
                    log.error("Error processing elicitation response.", throwable);
                    redisTemplate.delete(resultFieldsKey).subscribe();
                    return Mono.just(toStructuredResult(new McpSchema.ElicitResult(DECLINE, null), null));
                });
    }

    private Mono<StructuredElicitResult<ElicitationProvider.DeleteConfirmation>> readStoredResult(String resultFieldsKey) {
        return redisTemplate.opsForValue().getAndDelete(resultFieldsKey)
                .map(fieldsJson -> {
                    Map<String, Object> fieldsMap = deserializeFields(fieldsJson);
                    ElicitationProvider.DeleteConfirmation deleteConfirmation =
                            jsonMapper.convertValue(fieldsMap, ElicitationProvider.DeleteConfirmation.class);

                    return toStructuredResult(new McpSchema.ElicitResult(deleteConfirmation.action(), null), fieldsMap);
                });
    }

    private StructuredElicitResult<ElicitationProvider.DeleteConfirmation> toStructuredResult(McpSchema.ElicitResult elicitResult, Map<String, Object> fieldsMap) {
        ElicitationProvider.DeleteConfirmation deleteConfirmation = null;

        if (fieldsMap != null) {
            try {
                deleteConfirmation = jsonMapper.convertValue(fieldsMap, ElicitationProvider.DeleteConfirmation.class);
            } catch (IllegalArgumentException convertException) {
                log.warn("Failed to convert elicitation fields to DeleteConfirmation: {}", convertException.getMessage());
            }
        }

        return new StructuredElicitResult<>(elicitResult.action(), deleteConfirmation, fieldsMap);
    }

    private String serializeEventMessage(String event, Object data) {
        String serializedData = (data instanceof String stringData)
                ? stringData
                : jsonMapper.writeValueAsString(data);
        return jsonMapper.writeValueAsString(Map.of("event", event, "data", serializedData));
    }

    private ServerSentEvent<?> deserializeEventMessage(String json) {
        Map<String, Object> wrapper = jsonMapper.readValue(json, new TypeReference<>() {});
        String event = (String) wrapper.get("event");
        String data = (String) wrapper.get("data");
        return ServerSentEvent.builder(data).event(event).build();
    }

    private Map<String, Object> deserializeFields(String json) {
        return jsonMapper.readValue(json, new TypeReference<>() {});
    }

    private static String eventsChannelKey(UUID chatId) {
        return EVENTS_CHANNEL_PREFIX + chatId;
    }

    private static String fieldsKey(UUID chatId, UUID elicitationId) {
        return FIELDS_KEY_PREFIX + chatId + ":" + elicitationId;
    }
}