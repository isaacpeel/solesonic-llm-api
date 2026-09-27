package com.solesonic.service.chat.events;

import com.agui.community.core.message.ToolMessage;
import com.solesonic.mcp.client.elicitation.ElicitationProvider;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.service.chat.ChatMessageService;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.modelcontextprotocol.spec.McpSchema.ElicitResult.Action.*;

@Service
public class ElicitationService {
    private static final Logger log = LoggerFactory.getLogger(ElicitationService.class);
    public static final String ELICITATION_ID = "elicitationId";
    public static final String CHAT_ID = "chatId";
    public static final String ELICITATION = "elicitation";
    public static final String CANCEL_ACTION = "cancel";
    private static final String ACTION = "action";
    private static final String CONTENT = "content";
    private static final String SUMMARY = "summary";
    private static final String PROPERTIES = "properties";
    private static final String ONE_OF = "oneOf";
    private static final String CONST = "const";
    private static final String TITLE = "title";
    private static final String ENUM = "enum";
    private static final String ENUM_NAMES = "enumNames";
    private static final String SCHEMA_KEY_PREFIX = "elicitation:schema:";

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

    /**
     * Publishes the same {@code cancel} signal {@link com.solesonic.service.redis.RedisStreamingChatService}'s
     * {@code cancelEvents} already listens for, without requiring a pending elicitation to decline. This is
     * what lets a stop button end a turn that never asked the user anything.
     * <p>
     * Pub/sub delivers only to whoever is already subscribed — there is no replay. A signal sent in the
     * narrow window before {@code registerChat}'s listener is live is dropped with a zero receiver count,
     * silently, so that count is logged rather than discarded.
     */
    public Mono<Void> cancelChat(UUID chatId) {
        return redisTemplate.convertAndSend(eventsChannelKey(chatId), serializeEventMessage(CANCEL_ACTION, CANCEL_ACTION))
                .doOnNext(receiverCount -> {
                    if (receiverCount == 0) {
                        log.warn("Cancel signal for chat {} had no live subscriber — the turn may have missed it", chatId);
                    }
                })
                .then();
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

            //The requested properties are stored before the question is published, so they are
            //already in place by the time any client can answer it.
            storeRequestedProperties(chatId, elicitationId, request)
                    .then(Mono.defer(() -> redisTemplate.convertAndSend(eventsChannelKey(chatId), message)))
                    .subscribe(subscriberCount -> log.info("Emitted elicitation event to {} subscribers for chat {}", subscriberCount, chatId));
        } catch (IllegalArgumentException illegalArgumentException) {
            log.error("Failed to serialize elicitation request for chat {}", chatId, illegalArgumentException);
        }

        log.info("Finished emitting elicitation event for chat {}", chatId);
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

        return acceptedAnswer(elicitationActionResult)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(answer -> storeAndSignal(chatId, elicitationId, action,
                        answer.map(AcceptedAnswer::content).orElse(null),
                        answer.map(AcceptedAnswer::summary).orElse(null)));
    }

    /**
     * The narrowed content the MCP tool receives, paired with a human-readable rendering of the same
     * values — resolved against the stored {@code requestedSchema} property definitions, not just
     * their names, so an {@code enum}/{@code oneOf} const can be turned back into the label the user
     * was shown (e.g. an account id back into "Isaac"). {@code summary} is what chat history displays;
     * {@code content} is unchanged from what the tool receives.
     */
    private record AcceptedAnswer(Map<String, Object> content, String summary) {
    }

    /**
     * What the tool is allowed to see of an answer: on {@code accept}, the posted values narrowed to
     * the property names the elicitation asked for. Anything else the client sent — the UI pre-fills
     * {@code chatId}, for one — stays behind. Empty when nothing may be forwarded: any other action,
     * or a schema entry that has expired, since forwarding the raw payload then would hand the tool
     * whatever the client chose to send.
     */
    private Mono<AcceptedAnswer> acceptedAnswer(ElicitationProvider.ElicitationActionResult elicitationActionResult) {
        if (elicitationActionResult.action() != ACCEPT || elicitationActionResult.content() == null) {
            return Mono.empty();
        }

        UUID chatId = elicitationActionResult.chatId();
        UUID elicitationId = elicitationActionResult.elicitationId();

        return redisTemplate.opsForValue().getAndDelete(schemaKey(chatId, elicitationId))
                .flatMap(propertiesJson -> Mono.justOrEmpty(readProperties(propertiesJson)))
                .map(properties -> {
                    Map<String, Object> narrowed = new LinkedHashMap<>();

                    for (String propertyName : properties.keySet()) {
                        if (elicitationActionResult.content().containsKey(propertyName)) {
                            narrowed.put(propertyName, elicitationActionResult.content().get(propertyName));
                        }
                    }

                    return new AcceptedAnswer(narrowed, summarize(properties, narrowed));
                });
    }

    /**
     * An unreadable entry is treated as an expired one. Letting it error would skip the signal the
     * parked tool call is waiting on, leaving it to sit out the whole timeout.
     */
    private Optional<Map<String, Object>> readProperties(String propertiesJson) {
        try {
            return Optional.of(jsonMapper.readValue(propertiesJson, new TypeReference<LinkedHashMap<String, Object>>() {
            }));
        } catch (JacksonException jacksonException) {
            log.warn("Unreadable stored elicitation schema: {}", jacksonException.getClass().getSimpleName());

            return Optional.empty();
        }
    }

    /**
     * Renders the narrowed answer the way the user picked it, not the value the tool receives: for
     * each field, the matching {@code oneOf}/{@code enum} entry's label when the schema names one,
     * the raw value otherwise. Multiple fields join with {@code ", "}; a single field is its label
     * alone, which is what a one-question elicitation like an assignee picker resolves to.
     */
    private static String summarize(Map<String, Object> properties, Map<String, Object> content) {
        if (content.isEmpty()) {
            return null;
        }

        List<String> labels = new ArrayList<>();

        for (Map.Entry<String, Object> entry : content.entrySet()) {
            String label = label(properties.get(entry.getKey()), entry.getValue());

            if (label != null) {
                labels.add(label);
            }
        }

        if (labels.isEmpty()) {
            return null;
        }

        return String.join(", ", labels);
    }

    /**
     * The display label for one field's submitted value, resolved against that field's own JSON
     * Schema fragment: a {@code oneOf} list of {@code {const, title}} entries first, then a parallel
     * {@code enum}/{@code enumNames} pair, falling back to the raw value when the schema names no
     * label for it (a free-text field, or a value that matches no listed option).
     */
    private static String label(Object propertySchema, Object value) {
        if (value == null) {
            return null;
        }

        if (propertySchema instanceof Map<?, ?> schema) {
            if (schema.get(ONE_OF) instanceof List<?> oneOf) {
                for (Object candidate : oneOf) {
                    if (candidate instanceof Map<?, ?> entry
                            && String.valueOf(value).equals(String.valueOf(entry.get(CONST)))
                            && entry.get(TITLE) instanceof String title) {
                        return title;
                    }
                }
            }

            if (schema.get(ENUM) instanceof List<?> enumValues && schema.get(ENUM_NAMES) instanceof List<?> enumNames) {
                for (int index = 0; index < enumValues.size() && index < enumNames.size(); index++) {
                    if (String.valueOf(value).equals(String.valueOf(enumValues.get(index)))
                            && enumNames.get(index) instanceof String enumName) {
                        return enumName;
                    }
                }
            }
        }

        return String.valueOf(value);
    }

    private Mono<Boolean> storeAndSignal(UUID chatId,
                                         UUID elicitationId,
                                         McpSchema.ElicitResult.Action action,
                                         Map<String, Object> content,
                                         String summary) {
        Map<String, Object> answer = new HashMap<>();
        answer.put(ACTION, action.name());
        answer.put(CONTENT, content);

        if (summary != null) {
            answer.put(SUMMARY, summary);
        }

        String answerJson = jsonMapper.writeValueAsString(answer);

        Mono<Boolean> storeAndSignal = Mono.fromRunnable(() -> chatMessageService.updateElicitationResponse(chatId, elicitationId, answer))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.defer(() -> redisTemplate.opsForValue()
                        .set(fieldsKey(chatId, elicitationId), answerJson, Duration.ofSeconds(timeoutSeconds + 60))))
                .then(Mono.defer(() -> redisTemplate.convertAndSend(resultChannelKey(chatId, elicitationId), action.name())))
                .thenReturn(true);

        if (action == CANCEL) {
            log.info("Emitting cancel event for chat {}", chatId);
            return storeAndSignal.then(Mono.defer(() -> redisTemplate
                    .convertAndSend(eventsChannelKey(chatId), serializeEventMessage(CANCEL_ACTION, CANCEL_ACTION))
                    .thenReturn(true)));
        }

        return storeAndSignal;
    }

    /**
     * Reads an elicitation answer out of the AG-UI {@link ToolMessage} the client posts back. AG-UI
     * defines a tool message's content as a string, so the answer arrives as JSON inside it: the
     * action, with the form values alongside it. Content that names no known action is unreadable
     * rather than silently taken as a decline.
     */
    public Optional<ElicitationProvider.ElicitationActionResult> actionResult(UUID chatId,
                                                                             UUID elicitationId,
                                                                             ToolMessage toolMessage) {
        if (StringUtils.isBlank(toolMessage.content())) {
            return Optional.empty();
        }

        try {
            JsonNode answer = jsonMapper.readTree(toolMessage.content());
            JsonNode actionNode = answer.path(ACTION);

            if (!actionNode.isString()) {
                return Optional.empty();
            }

            McpSchema.ElicitResult.Action action =
                    McpSchema.ElicitResult.Action.valueOf(actionNode.asString().toUpperCase(Locale.ROOT));

            Map<String, Object> content = jsonMapper.convertValue(answer, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            content.remove(ACTION);

            return Optional.of(new ElicitationProvider.ElicitationActionResult(action, chatId, elicitationId, content));
        } catch (JacksonException | IllegalArgumentException exception) {
            log.warn("Unreadable elicitation response for chat {}: {}", chatId, exception.getClass().getSimpleName());

            return Optional.empty();
        }
    }

    /**
     * Waits for the client's answer and resolves it as the result the MCP tool receives: the action,
     * and on {@code accept} the narrowed content. Timing out, or the turn ending with the question
     * still open, resolves as a decline with no content.
     */
    public Mono<McpSchema.ElicitResult> awaitResultAsync(UUID chatId, UUID elicitationId) {
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
                            .map(answerJson -> new McpSchema.ElicitResult(resolvedAction,
                                    resolvedAction == ACCEPT ? storedContent(answerJson) : null))
                            .defaultIfEmpty(new McpSchema.ElicitResult(resolvedAction, null));
                })
                .publishOn(Schedulers.boundedElastic())
                .doFinally(_ ->
                    redisTemplate.opsForSet().remove(pendingSetKey(chatId), elicitationId.toString()).subscribe()
                )
                .onErrorResume(throwable -> {
                    log.error("Timeout or error awaiting elicitation for chat {} id {}: {}", chatId, elicitationId, throwable.getMessage());
                    return Mono.just(new McpSchema.ElicitResult(DECLINE, null));
                });
    }

    private Map<String, Object> storedContent(String answerJson) {
        try {
            JsonNode content = jsonMapper.readTree(answerJson).path(CONTENT);

            if (!content.isObject()) {
                return null;
            }

            return jsonMapper.convertValue(content, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JacksonException jacksonException) {
            log.warn("Unreadable stored elicitation answer: {}", jacksonException.getClass().getSimpleName());

            return null;
        }
    }

    private Mono<Boolean> storeRequestedProperties(UUID chatId, UUID elicitationId, McpSchema.ElicitRequest request) {
        Map<String, Object> properties = requestedProperties(request);

        if (properties.isEmpty()) {
            return Mono.just(false);
        }

        return redisTemplate.opsForValue().set(schemaKey(chatId, elicitationId),
                jsonMapper.writeValueAsString(properties), Duration.ofSeconds(timeoutSeconds + 60));
    }

    /**
     * Only a form elicitation asks for values; a URL elicitation sends the user elsewhere and has
     * nothing to forward. Stored by full property schema rather than name alone, so an accepted
     * answer can later be resolved back to the label the user picked, not just narrowed by key.
     */
    private static Map<String, Object> requestedProperties(McpSchema.ElicitRequest request) {
        if (!(request instanceof McpSchema.ElicitFormRequest formRequest) || formRequest.requestedSchema() == null) {
            return Map.of();
        }

        if (!(formRequest.requestedSchema().get(PROPERTIES) instanceof Map<?, ?> properties)) {
            return Map.of();
        }

        Map<String, Object> byName = new LinkedHashMap<>();
        properties.forEach((name, schema) -> byName.put(String.valueOf(name), schema));

        return byName;
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

    private static String schemaKey(UUID chatId, UUID elicitationId) {
        return SCHEMA_KEY_PREFIX + chatId + ":" + elicitationId;
    }

    private static String pendingSetKey(UUID chatId) {
        return PENDING_SET_PREFIX + chatId;
    }
}
