package com.solesonic.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.TimeUnit;

import static io.modelcontextprotocol.spec.McpSchema.ElicitResult.Action.*;

@Service
public class ElicitationService {
    private static final Logger log = LoggerFactory.getLogger(ElicitationService.class);

    public static final class ElicitationHandle {
        private final UUID elicitationId;
        private final CompletableFuture<McpSchema.ElicitResult> future;

        public ElicitationHandle(UUID elicitationId, CompletableFuture<McpSchema.ElicitResult> future) {
            this.elicitationId = elicitationId;
            this.future = future;
        }

        public UUID getElicitationId() {
            return elicitationId;
        }

        public CompletableFuture<McpSchema.ElicitResult> getFuture() {
            return future;
        }
    }

    private final ObjectMapper objectMapper;

    @Value("${solesonic.elicitation.timeout-seconds:600}")
    private long timeoutSeconds;

    private final ConcurrentHashMap<UUID, Sinks.Many<ServerSentEvent<?>>> chatSinks = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, CompletableFuture<McpSchema.ElicitResult>> pendingById = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, UUID> nameIndex = new ConcurrentHashMap<>();

    public ElicitationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Flux<ServerSentEvent<?>> registerChat(UUID chatId) {
        Sinks.Many<ServerSentEvent<?>> sink = chatSinks.computeIfAbsent(chatId, id ->
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

        if (name != null && !name.isBlank()) {
            String nameKey = nameKey(chatId, name);
            nameIndex.put(nameKey, elicitationId);
        }

        return new ElicitationHandle(elicitationId, future);
    }

    public void emitElicitation(UUID chatId, UUID elicitationId, McpSchema.ElicitRequest request) {
        log.debug("Emitting elicitation for chat id {}", chatId);
        Sinks.Many<ServerSentEvent<?>> sink = chatSinks.get(chatId);

        if (sink == null) {
            log.warn("No SSE sink found for chat {} while emitting elicitation", chatId);
            return;
        }

        try {
            Map<String, Object> requestJson = objectMapper.convertValue(request, Map.class);
            requestJson.put("elicitationId", elicitationId.toString());
            requestJson.put("chatId", chatId.toString());

            log.info("Emitting elicitation event for chat {}", chatId);

            ServerSentEvent<?> event = ServerSentEvent.builder(requestJson)
                    .event("elicitation")
                    .build();

            sink.tryEmitNext(event);
        } catch (IllegalArgumentException ex) {
            log.error("Failed to serialize elicitation request for chat {}", chatId, ex);
        }

        log.info("Finished emitting elicitation event for chat {}", chatId);
    }

    public boolean completeFromFrontend(UUID chatId, UUID elicitationId, String name, Map<String, Object> fields) {
        log.debug("Completing form elicitation for chat id {}", chatId);
        UUID effectiveId = elicitationId;

        if (effectiveId == null && name != null) {
            effectiveId = nameIndex.remove(nameKey(chatId, name));
        }

        if (effectiveId == null) {
            log.warn("No elicitation id or known name for chat {}", chatId);
            return false;
        }

        String idKey = idKey(chatId, effectiveId);

        CompletableFuture<McpSchema.ElicitResult> future = pendingById.remove(idKey);

        if (future == null) {
            log.warn("No pending elicitation future found for chat {} id {}", chatId, effectiveId);
            return false;
        }

        log.info("Received elicitation fields: {}", fields);

        Object confirmed = fields.get("confirmed");
        log.info("Elicitation confirmed: {}", confirmed);

        McpSchema.ElicitResult result;

        if (!(confirmed instanceof String confirmedValue)) {
            throw new IllegalStateException("Expected string value for 'confirmed', got: " + confirmed);
        }

        McpSchema.ElicitResult.Action action = switch (confirmedValue.toLowerCase()) {
            case "accept" -> ACCEPT;
            case "decline" -> DECLINE;
            case "cancel" -> CANCEL;
            default -> throw new IllegalStateException("Unexpected value: " + confirmedValue);
        };

        result = new McpSchema.ElicitResult(action, fields);

        log.info("Completing elicitation for chat {}", chatId);
        return future.complete(result);
    }

    public McpSchema.ElicitResult awaitResult(UUID chatId, UUID elicitationId) {
        String idKey = idKey(chatId, elicitationId);
        CompletableFuture<McpSchema.ElicitResult> future = pendingById.get(idKey);

        if (future == null) {
            log.warn("Attempted to await non-existent elicitation future for chat {} id {}", chatId, elicitationId);

            return new McpSchema.ElicitResult(DECLINE, null);
        }

        try {
            McpSchema.ElicitResult result = future.get(timeoutSeconds, TimeUnit.SECONDS);

            return Optional.ofNullable(result)
                    .orElseGet(() -> new McpSchema.ElicitResult(DECLINE, null));

        } catch (Exception ex) {
            log.warn("Timeout or interruption while awaiting elicitation for chat {} id {}: {}", chatId, elicitationId, ex.getMessage());

            pendingById.remove(idKey);

            return new McpSchema.ElicitResult(DECLINE, null);
        }
    }

    public Mono<McpSchema.ElicitResult> awaitResultAsync(UUID chatId, UUID elicitationId) {
        String idKey = idKey(chatId, elicitationId);
        CompletableFuture<McpSchema.ElicitResult> future = pendingById.get(idKey);

        if (future == null) {
            log.warn("Attempted to await non-existent elicitation future for chat {} id {}", chatId, elicitationId);

            return Mono.just(new McpSchema.ElicitResult(DECLINE, null));
        }

        Duration timeout = Duration.ofSeconds(timeoutSeconds);

        return Mono.fromFuture(future)
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(timeout)
                .map(result -> Optional.ofNullable(result).orElseGet(() -> new McpSchema.ElicitResult(DECLINE, null)))
                .onErrorResume(ex -> {
                    log.warn("Timeout or error while awaiting elicitation for chat {} id {}: {}", chatId, elicitationId, ex.getMessage());

                    pendingById.remove(idKey);

                    return Mono.just(new McpSchema.ElicitResult(DECLINE, null));
                });
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
