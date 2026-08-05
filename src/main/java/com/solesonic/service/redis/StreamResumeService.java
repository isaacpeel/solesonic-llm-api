package com.solesonic.service.redis;

import com.solesonic.redis.model.StreamEventId;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.service.chat.ChatStreamAccessService;
import com.solesonic.service.chat.ChatStreamAccessService.ChatAccess;
import com.solesonic.util.SseResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.service.redis.RedisStreamingChatService.DONE;

/**
 * Resumes a turn a client stopped listening to.
 * <p>
 * A turn keeps generating and persisting whether or not anyone is connected, and every frame it
 * emits lands in a Redis stream — so recovery is a matter of replaying from the client's cursor
 * and then continuing live. What this class adds over a bare subscription is knowing when
 * <em>not</em> to: an unknown chat, an expired buffer or a turn that already finished are all
 * answered with a status code before a byte of body is written.
 * <p>
 * That distinction is the whole point. Subscribing to a stream that will never receive another
 * frame hangs until the client gives up, and a client that hangs cannot fall back to polling
 * {@code GET /chats/{chatId}} — which is the recovery path that always works.
 * <p>
 * Statuses returned here are decided synchronously, on the request thread, so they reach the
 * client as real HTTP statuses rather than as an error frame on a committed 200 response.
 */
@Service
public class StreamResumeService {
    private static final Logger log = LoggerFactory.getLogger(StreamResumeService.class);

    private final RedisStreamService redisStreamService;
    private final ChatStreamAccessService chatStreamAccessService;
    private final Duration lookupTimeout;

    public StreamResumeService(RedisStreamService redisStreamService,
                               ChatStreamAccessService chatStreamAccessService,
                               @Value("${redis.stream.lookup-timeout-seconds:5}") long lookupTimeoutSeconds) {
        this.redisStreamService = redisStreamService;
        this.chatStreamAccessService = chatStreamAccessService;
        this.lookupTimeout = Duration.ofSeconds(lookupTimeoutSeconds);
    }

    public ResponseEntity<Flux<ServerSentEvent<?>>> resume(Authentication authentication,
                                                           UUID chatId,
                                                           UUID userId,
                                                           String lastEventId) {

        ChatAccess chatAccess = chatStreamAccessService.forExistingChat(authentication, chatId, userId);

        if (chatAccess == ChatAccess.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (chatAccess == ChatAccess.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }

        String cursor = normalizeCursor(lastEventId);

        if (cursor == null) {
            log.info("Rejecting resume of chat {} — unparseable cursor '{}'", chatId, lastEventId);

            return ResponseEntity.badRequest().build();
        }

        Optional<RedisStreamService.StreamTail> tail;
        Optional<String> earliestEventId;

        try {
            tail = await(redisStreamService.tail(chatId, userId));
            earliestEventId = await(redisStreamService.getEarliestOffset(chatId, userId));
        } catch (RuntimeException runtimeException) {
            log.error("Failed to inspect stream for chat {} while resuming", chatId, runtimeException);

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        if (tail.isEmpty()) {
            log.info("Resume of chat {} is gone — no buffered frames remain", chatId);

            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        if (!StreamEventId.BEGINNING.equals(cursor)) {
            if (hasGap(cursor, earliestEventId)) {
                log.info("Resume of chat {} is gone — cursor {} predates the oldest retained frame {}",
                        chatId, cursor, earliestEventId.orElse(null));

                return ResponseEntity.status(HttpStatus.GONE).build();
            }

            if (isCaughtUpOnFinishedTurn(cursor, tail.get())) {
                log.debug("Resume of chat {} has nothing to send — turn finished at {}", chatId, cursor);

                return ResponseEntity.noContent().build();
            }
        }

        log.info("Resuming chat {} for user {} from {}", chatId, userId, cursor);

        return SseResponse.ok(redisStreamService.subscribe(chatId, userId, cursor));
    }

    /**
     * @return the cursor to read from, or {@code null} if the client sent something that is not a
     * stream id at all — which is a client bug worth reporting rather than replaying from zero.
     */
    private String normalizeCursor(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return StreamEventId.BEGINNING;
        }

        String trimmed = lastEventId.trim();

        if (StreamEventId.parse(trimmed).isEmpty()) {
            return null;
        }

        if (StreamEventId.isBeginning(trimmed)) {
            return StreamEventId.BEGINNING;
        }

        return trimmed;
    }

    /**
     * Whether the frames immediately after the cursor have already been trimmed away. Replaying
     * from a cursor older than the oldest surviving frame would silently drop content out of the
     * middle of the assistant's message, which is worse than telling the client to go and poll.
     */
    private boolean hasGap(String cursor, Optional<String> earliestEventId) {
        return StreamEventId.parse(cursor)
                .flatMap(cursorId -> earliestEventId
                        .flatMap(StreamEventId::parse)
                        .map(earliestId -> cursorId.compareTo(earliestId) < 0))
                .orElse(false);
    }

    /**
     * The client already holds every frame of a finished turn, {@code done} included. Subscribing
     * would wait forever on a stream nothing will ever write to again.
     */
    private boolean isCaughtUpOnFinishedTurn(String cursor, RedisStreamService.StreamTail tail) {
        if (!DONE.equalsIgnoreCase(tail.type())) {
            return false;
        }

        return StreamEventId.parse(cursor)
                .flatMap(cursorId -> StreamEventId.parse(tail.eventId())
                        .map(tailId -> cursorId.compareTo(tailId) >= 0))
                .orElse(false);
    }

    private <T> Optional<T> await(Mono<T> lookup) {
        return Optional.ofNullable(lookup.block(lookupTimeout));
    }
}
