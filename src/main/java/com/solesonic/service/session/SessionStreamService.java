package com.solesonic.service.session;

import com.solesonic.model.session.SharedSession;
import com.solesonic.redis.model.StreamEventId;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.util.SseResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * A participant's view of a shared session's stream: every run, whoever started it, until the
 * participant goes. Unlike a chat's resume it never stops at a terminal frame, and an empty or expired
 * stream is not gone: a session can sit for hours between messages, and the next one writes to the
 * same key.
 * <p>
 * Without a cursor a reader starts at the run in progress, if there is one, so a participant who opens
 * a session mid-run sees that run from its {@code RUN_STARTED}; otherwise at the stream's end. With
 * one, a cursor older than the oldest frame still kept is a {@code 410}, and the client reloads the
 * transcript and reads on from the end.
 */
@Service
public class SessionStreamService {
    private static final Logger log = LoggerFactory.getLogger(SessionStreamService.class);
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    private final SharedSessionService sharedSessionService;
    private final SessionRunLock sessionRunLock;
    private final RedisStreamService redisStreamService;

    public SessionStreamService(SharedSessionService sharedSessionService, SessionRunLock sessionRunLock, RedisStreamService redisStreamService) {
        this.sharedSessionService = sharedSessionService;
        this.sessionRunLock = sessionRunLock;
        this.redisStreamService = redisStreamService;
    }

    public ResponseEntity<Flux<ServerSentEvent<?>>> watch(UUID sessionId, UUID readerUserId, String lastEventId) {
        SharedSession sharedSession = sharedSessionService.requireParticipant(sessionId, readerUserId);
        UUID chatId = sharedSession.getChatId();
        UUID streamUserId = SharedSessionService.streamOwner(sharedSessionService.participants(sessionId));

        String cursor;

        try {
            if (lastEventId == null || lastEventId.isBlank()) {
                cursor = sessionRunLock.cursor(sessionId)
                        .orElseGet(() -> redisStreamService.getLatestOffset(chatId, streamUserId).block(LOOKUP_TIMEOUT));
            } else {
                Optional<StreamEventId> requested = StreamEventId.parse(lastEventId);

                if (requested.isEmpty()) {
                    return ResponseEntity.badRequest().build();
                }

                if (hasGap(requested.get(), chatId, streamUserId)) {
                    log.info("Watch of shared session {} is gone: the cursor predates the oldest kept frame", sessionId);
                    return ResponseEntity.status(HttpStatus.GONE).build();
                }

                cursor = lastEventId.trim();
            }
        } catch (RuntimeException exception) {
            log.error("Failed to inspect the stream of shared session {}: {}", sessionId, exception.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        log.info("Watching shared session {}", sessionId);

        return SseResponse.ok(redisStreamService.follow(chatId, streamUserId, readerUserId,
                cursor == null ? StreamEventId.BEGINNING : cursor));
    }

    private boolean hasGap(StreamEventId requested, UUID chatId, UUID streamUserId) {
        if (requested.milliseconds() == 0L && requested.sequence() == 0L) {
            return false;
        }

        return Optional.ofNullable(redisStreamService.getEarliestOffset(chatId, streamUserId).block(LOOKUP_TIMEOUT))
                .flatMap(StreamEventId::parse)
                .map(earliest -> requested.compareTo(earliest) < 0)
                .orElse(false);
    }
}
