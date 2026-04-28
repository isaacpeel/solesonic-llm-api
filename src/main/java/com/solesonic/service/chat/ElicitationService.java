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
import reactor.core.scheduler.Schedulers;
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
    private static final String RESULT_CHANNEL_PREFIX = "elicitation:result:";
    private static final String FIELDS_KEY_PREFIX = "elicitation:fields:";
    private static final String PENDING_SET_PREFIX = "elicitation:pending:";

    public record ElicitationHandle(UUID elicitationId) {
    }

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

        String pendingKey = pendingSetKey(chatId);
        redisTemplate.opsForSet().members(pendingKey)
                .flatMap(elicitationIdString ->
                    redisTemplate.convertAndSend(
                        resultChannelKey(chatId, UUID.fromString(elicitationIdString)),
                        DECLINE.name()
                    )
                )
                .then(redisTemplate.delete(pendingKey))
                .subscribe(deleted -> log.debug("Closed {} pending elicitations for chat {}", deleted, chatId));
    }

    public ElicitationHandle prepareElicitation(UUID chatId) {
        UUID elicitationId = UUID.randomUUID();
        redisTemplate.opsForSet()
                .add(pendingSetKey(chatId), elicitationId.toString())
                .flatMap(_ -> redisTemplate.expire(pendingSetKey(chatId), Duration.ofSeconds(timeoutSeconds + 60)))
                .subscribe();
        return new ElicitationHandle(elicitationId);
    }

    public void emitElicitation(UUID chatId, UUID elicitationId, McpSchema.ElicitRequest request) {
        log.debug("Emitting elicitation for chat id {}", chatId);

        try {
            Map<String, Object> requestJson = jsonMapper.convertValue(request, new TypeReference<>() {
            });
            requestJson.put(ELICITATION_ID, elicitationId.toString());
            requestJson.put(CHAT_ID, chatId.toString());

            log.info("Emitting elicitation event for chat {}", chatId);

            String elicitationMessage = request.message();
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setMessage(elicitationMessage);
            chatMessage.setChatId(chatId);
            chatMessage.setMessageType(MessageType.SYSTEM);
            chatMessage.setElicitationId(elicitationId);
            chatMessageService.save(chatMessage);

            String message = jsonMapper.writeValueAsString(Map.of("event", ELICITATION, "data", requestJson));

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
            Map<String, Object> progressJson = jsonMapper.convertValue(progressNotification, new TypeReference<>() {
            });
            progressJson.put(CHAT_ID, chatId.toString());

            String message = serializeEventMessage(PROGRESS, progressJson);

            redisTemplate.convertAndSend(eventsChannelKey(chatId), message)
                    .subscribe(subscriberCount -> log.debug("Emitted progress event to {} subscribers for chat {}", subscriberCount, chatId));
        } catch (IllegalArgumentException illegalArgumentException) {
            log.info("Failed to serialize progress notification for chat {}", chatId, illegalArgumentException);
        }
    }

    public Mono<Boolean> completeFromFrontend(ElicitationProvider.ElicitationActionResult elicitationActionResult) {
        log.info("Completing elicitation for chat {}", elicitationActionResult.chatId());

        McpSchema.ElicitResult.Action action = elicitationActionResult.action();
        if (action == null) {
            log.warn("Missing action in elicitation response for chat {}", elicitationActionResult.chatId());
            return Mono.just(false);
        }

        log.info("Elicitation action: {}", action);

        UUID chatId = elicitationActionResult.chatId();
        UUID elicitationId = elicitationActionResult.elicitationId();

        log.info("Response for chat id: {}", chatId);
        log.info("Response for elicitationId: {}", elicitationId);

        Map<String, Object> fieldsMap = jsonMapper.convertValue(elicitationActionResult, new TypeReference<>() {});
        chatMessageService.updateElicitationResponse(chatId, elicitationId, fieldsMap);

        String fieldsJson = jsonMapper.writeValueAsString(fieldsMap);

        Mono<Boolean> storeAndSignal = redisTemplate.opsForValue()
                .set(fieldsKey(chatId, elicitationId), fieldsJson, Duration.ofSeconds(timeoutSeconds + 60))
                .then(redisTemplate.convertAndSend(resultChannelKey(chatId, elicitationId), action.name()))
                .thenReturn(true);

        if (action == CANCEL) {
            log.info("Emitting cancel event for chat {}", chatId);
            return storeAndSignal.then(
                redisTemplate.convertAndSend(eventsChannelKey(chatId), serializeEventMessage(CANCEL_ACTION, CANCEL_ACTION))
                    .thenReturn(true)
            );
        }

        return storeAndSignal;
    }

    public Mono<StructuredElicitResult<ElicitationProvider.ElicitationActionResult>> awaitResultAsync(UUID chatId, UUID elicitationId) {
        return redisTemplate.listenToChannel(resultChannelKey(chatId, elicitationId))
                .next()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .flatMap(message -> {
                    String actionName = message.getMessage();
                    log.info("Elicitation future resolved: {}", actionName);
                    McpSchema.ElicitResult.Action action;
                    try {
                        action = valueOf(actionName);
                    } catch (IllegalArgumentException illegalArgumentException) {
                        log.warn("Unknown elicitation action '{}', defaulting to DECLINE", actionName);
                        action = DECLINE;
                    }
                    final McpSchema.ElicitResult.Action resolvedAction = action;

                    return redisTemplate.opsForValue().getAndDelete(fieldsKey(chatId, elicitationId))
                            .map(fieldsJson -> {
                                Map<String, Object> fieldsMap = deserializeFields(fieldsJson);
                                return toStructuredResult(new McpSchema.ElicitResult(resolvedAction, fieldsMap), fieldsMap);
                            })
                            .defaultIfEmpty(toStructuredResult(new McpSchema.ElicitResult(resolvedAction, null), null));
                })
                .publishOn(Schedulers.boundedElastic())
                .doFinally(_ ->
                    redisTemplate.opsForSet().remove(pendingSetKey(chatId), elicitationId.toString()).subscribe()
                )
                .onErrorResume(throwable -> {
                    log.error("Timeout or error awaiting elicitation for chat {} id {}: {}", chatId, elicitationId, throwable.getMessage());
                    return Mono.just(toStructuredResult(new McpSchema.ElicitResult(DECLINE, null), null));
                });
    }

    private Map<String, Object> deserializeFields(String fieldsJson) {
        if (fieldsJson == null || fieldsJson.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(fieldsJson, new TypeReference<>() {});
        } catch (Exception exception) {
            log.warn("Failed to deserialize elicitation fields: {}", exception.getMessage());
            return null;
        }
    }

    private StructuredElicitResult<ElicitationProvider.ElicitationActionResult> toStructuredResult(McpSchema.ElicitResult elicitResult, Map<String, Object> fieldsMap) {
        ElicitationProvider.ElicitationActionResult elicitationActionResult = null;

        if (fieldsMap != null) {
            try {
                elicitationActionResult = jsonMapper.convertValue(fieldsMap, ElicitationProvider.ElicitationActionResult.class);
            } catch (IllegalArgumentException convertException) {
                log.warn("Failed to convert elicitation fields to DeleteConfirmation: {}", convertException.getMessage());
            }
        }

        return new StructuredElicitResult<>(elicitResult.action(), elicitationActionResult, fieldsMap);
    }

    private String serializeEventMessage(String event, Object data) {
        return jsonMapper.writeValueAsString(Map.of("event", event, "data", data));
    }

    private ServerSentEvent<?> deserializeEventMessage(String json) {
        Map<String, Object> wrapper = jsonMapper.readValue(json, new TypeReference<>() {
        });
        String event = (String) wrapper.get("event");
        Object data = wrapper.get("data");
        String dataJson = (data instanceof String stringData)
                ? stringData
                : jsonMapper.writeValueAsString(data);

        return ServerSentEvent.builder(dataJson).event(event).build();
    }

    private static String eventsChannelKey(UUID chatId) {
        return EVENTS_CHANNEL_PREFIX + chatId;
    }

    private static String resultChannelKey(UUID chatId, UUID elicitationId) {
        return RESULT_CHANNEL_PREFIX + chatId + ":" + elicitationId;
    }

    private static String fieldsKey(UUID chatId, UUID elicitationId) {
        return FIELDS_KEY_PREFIX + chatId + ":" + elicitationId;
    }

    private static String pendingSetKey(UUID chatId) {
        return PENDING_SET_PREFIX + chatId;
    }
}
