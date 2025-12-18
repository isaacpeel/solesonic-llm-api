package com.solesonic.service.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.mcp.client.elicitation.ElicitationProvider;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.service.ollama.ChatMessageService;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.context.StructuredElicitResult;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static io.modelcontextprotocol.spec.McpSchema.ElicitResult.Action.*;

@Service
public class ElicitationService {
    private static final Logger log = LoggerFactory.getLogger(ElicitationService.class);
    public static final String ELICITATION_ID = "elicitationId";
    public static final String CHAT_ID = "chatId";
    public static final String ELICITATION = "elicitation";
    public static final String CONFIRMED = "confirmed";
    public static final String ACCEPT_ACTION = "accept";
    public static final String DECLINE_ACTION = "decline";
    public static final String CANCEL_ACTION = "cancel";

    public record ElicitationHandle(UUID elicitationId, CompletableFuture<McpSchema.ElicitResult> future) {}

    private final ObjectMapper objectMapper;
    private final ChatMessageService chatMessageService;

    @Value("${solesonic.elicitation.timeout-seconds:600}")
    private long timeoutSeconds;

    private final ConcurrentHashMap<UUID, Sinks.Many<ServerSentEvent<?>>> chatSinks = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, CompletableFuture<McpSchema.ElicitResult>> pendingById = new ConcurrentHashMap<>();

    // Store the raw fields submitted from the frontend per elicitation id to reconstruct structured results
    private final ConcurrentHashMap<String, Map<String, Object>> resultFieldsById = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, UUID> nameIndex = new ConcurrentHashMap<>();

    public ElicitationService(ObjectMapper objectMapper,
                              ChatMessageService chatMessageService) {
        this.objectMapper = objectMapper;
        this.chatMessageService = chatMessageService;
    }

    public Flux<ServerSentEvent<?>> registerChat(UUID chatId) {
        Sinks.Many<ServerSentEvent<?>> sink = chatSinks.computeIfAbsent(chatId, _ ->
                Sinks.many().multicast().onBackpressureBuffer());

        return sink.asFlux();
    }

    public void closeChat(UUID chatId) {
        Sinks.Many<ServerSentEvent<?>> sink = chatSinks.remove(chatId);

        if (sink != null) {
            sink.tryEmitComplete();
        }

        // Clean up any pending elicitations for this chat id
        pendingById.keySet().removeIf(key -> key.startsWith(keyPrefix(chatId)));
        nameIndex.keySet().removeIf(key -> key.startsWith(keyPrefix(chatId)));
    }

    public ElicitationHandle prepareElicitation(UUID chatId, String name) {
        UUID elicitationId = UUID.randomUUID();
        String idKey = idKey(chatId, elicitationId);

        CompletableFuture<McpSchema.ElicitResult> future = new CompletableFuture<>();

        pendingById.put(idKey, future);

        if (StringUtils.isNotEmpty(name)) {
            String nameKey = nameKey(chatId, name);
            nameIndex.put(nameKey, elicitationId);
        }

        return new ElicitationHandle(elicitationId, future);
    }

    public void emitElicitation(UUID chatId, UUID elicitationId, McpSchema.ElicitRequest request) {
        log.debug("Emitting elicitation for chat id {}", chatId);
        Sinks.Many<ServerSentEvent<?>> serverSentEventMany = chatSinks.get(chatId);

        if (serverSentEventMany == null) {
            log.warn("No SSE sink found for chat {} while emitting elicitation", chatId);
            return;
        }

        try {
            Map<String, Object> requestJson = objectMapper.convertValue(request, new TypeReference<>() {});

            requestJson.put(ELICITATION_ID, elicitationId.toString());
            requestJson.put(CHAT_ID, chatId.toString());

            log.info("Emitting elicitation event for chat {}", chatId);

            ServerSentEvent<?> serverSentEvent = ServerSentEvent.builder(requestJson)
                    .event(ELICITATION)
                    .build();

            String elicitationMessage = request.message();
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setMessage(elicitationMessage);
            chatMessage.setChatId(chatId);
            chatMessage.setMessageType(MessageType.SYSTEM);

            chatMessageService.save(chatMessage);

            serverSentEventMany.tryEmitNext(serverSentEvent);
        } catch (IllegalArgumentException ex) {
            log.error("Failed to serialize elicitation request for chat {}", chatId, ex);
        }

        log.info("Finished emitting elicitation event for chat {}", chatId);
    }

    public boolean completeFromFrontend(UUID chatId, UUID elicitationId, String name, Map<String, Object> fields) {
        log.info("Completing form elicitation for chat id {}", chatId);
        
        UUID effectiveId = elicitationId;

        if (effectiveId == null && name != null) {
            effectiveId = nameIndex.remove(nameKey(chatId, name));
        }

        if (effectiveId == null) {
            log.warn("No elicitation id or known name for chat {}", chatId);
            return false;
        }

        String idKey = idKey(chatId, effectiveId);

        CompletableFuture<McpSchema.ElicitResult> future = pendingById.get(idKey);

        if (future == null) {
            log.warn("No pending elicitation future found for chat {} id {}", chatId, effectiveId);
            return false;
        }

        log.info("Received elicitation fields: {}", fields);
        
        Object confirmedField = fields.get(CONFIRMED);
        
        log.info("Elicitation confirmed: {}", confirmedField);
        
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setMessage(confirmedField.toString());
        chatMessage.setChatId(chatId);
        chatMessage.setMessageType(MessageType.USER);

        chatMessageService.save(chatMessage);

        McpSchema.ElicitResult elicitResult = elicitResult(fields, confirmedField);

        // Store fields so we can build a StructuredElicitResult later
        resultFieldsById.put(idKey, fields);

        // If the user canceled, emit a cancel control event on the SSE stream for this chat
        if (elicitResult.action() == CANCEL) {
            Sinks.Many<ServerSentEvent<?>> serverSentEventMany = chatSinks.get(chatId);

            if (serverSentEventMany != null) {
                log.info("Emitting cancel event for chat {}", chatId);
                
                ServerSentEvent<?> cancelEvent = ServerSentEvent.builder("cancel")
                        .event("cancel")
                        .build();

                serverSentEventMany.tryEmitNext(cancelEvent);
            } else {
                log.warn("No SSE sink found for chat {} while emitting cancel event", chatId);
            }
        }

        
        log.info("Completing elicitation for chat {}", chatId);
        
        return future.complete(elicitResult);
    }

    private static McpSchema.ElicitResult elicitResult(Map<String, Object> fields, Object confirmed) {
        McpSchema.ElicitResult result;

        if (!(confirmed instanceof String confirmedValue)) {
            throw new IllegalStateException("Expected string value for 'confirmed', got: " + confirmed);
        }

        McpSchema.ElicitResult.Action action = switch (confirmedValue.toLowerCase()) {
            case ACCEPT_ACTION -> ACCEPT;
            case DECLINE_ACTION -> DECLINE;
            case CANCEL_ACTION -> CANCEL;
            default -> throw new IllegalStateException("Unexpected value: " + confirmedValue);
        };

        result = new McpSchema.ElicitResult(action, fields);
        return result;
    }

    public Mono<StructuredElicitResult<ElicitationProvider.DeleteConfirmation>> awaitResultAsync(UUID chatId, UUID elicitationId) {
        String compositeIdKey = idKey(chatId, elicitationId);
        CompletableFuture<McpSchema.ElicitResult> elicitationFuture = pendingById.get(compositeIdKey);

        if (elicitationFuture == null) {
            log.warn("Attempted to await non-existent elicitation future for chat {} id {}", chatId, elicitationId);

            McpSchema.ElicitResult declineResult = new McpSchema.ElicitResult(DECLINE, null);

            return Mono.just(toStructuredResult(declineResult, null));
        }

        Duration timeout = Duration.ofSeconds(timeoutSeconds);

        return Mono.fromFuture(elicitationFuture)
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(timeout)
                .doOnCancel(() -> {
                    log.debug("Elicitation await cancelled for chat {} id {}", chatId, elicitationId);
                    pendingById.remove(compositeIdKey);
                    resultFieldsById.remove(compositeIdKey);
                })
                .map(result -> {
                    McpSchema.ElicitResult safeResult = Optional.ofNullable(result)
                            .orElseGet(() -> new McpSchema.ElicitResult(DECLINE, null));

                    log.info("Elicitation future result: {}", safeResult.action().name());

                    Map<String, Object> fieldsMap = resultFieldsById.remove(compositeIdKey);

                    return toStructuredResult(safeResult, fieldsMap);
                })
                .onErrorResume(throwable -> {
                    log.warn("Timeout or error while awaiting elicitation for chat {} id {}: {}", chatId, elicitationId, throwable.getMessage());

                    pendingById.remove(compositeIdKey);

                    Map<String, Object> fieldsMap = resultFieldsById.remove(compositeIdKey);

                    McpSchema.ElicitResult declineResult = new McpSchema.ElicitResult(DECLINE, null);

                    return Mono.just(toStructuredResult(declineResult, fieldsMap));
                });
    }

    private StructuredElicitResult<ElicitationProvider.DeleteConfirmation> toStructuredResult(McpSchema.ElicitResult elicitResult, Map<String, Object> fieldsMap) {
        ElicitationProvider.DeleteConfirmation deleteConfirmation = null;

        if (fieldsMap != null) {
            try {
                deleteConfirmation = objectMapper.convertValue(fieldsMap, ElicitationProvider.DeleteConfirmation.class);
            } catch (IllegalArgumentException convertException) {
                log.warn("Failed to convert elicitation fields to DeleteConfirmation: {}", convertException.getMessage());
            }
        }

        return new StructuredElicitResult<>(elicitResult.action(), deleteConfirmation, fieldsMap);
    }

    private static String idKey(UUID chatId, UUID elicitationId) {
        return keyPrefix(chatId) + elicitationId.toString();
    }

    private static String nameKey(UUID chatId, String name) {
        return keyPrefix(chatId) + name;
    }

    private static String keyPrefix(UUID chatId) {
        return chatId.toString() + ":";
    }
}
