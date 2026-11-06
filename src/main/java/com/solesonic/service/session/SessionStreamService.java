package com.solesonic.service.session;

import com.solesonic.exception.session.SessionException;
import com.solesonic.model.session.SessionMode;
import com.solesonic.model.session.SharedSession;
import com.solesonic.redis.model.StreamEventId;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.util.SseResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

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
    private static final Duration RECHECK = Duration.ofSeconds(10);

    private final SharedSessionService sharedSessionService;
    private final SessionRunLock sessionRunLock;
    private final RedisStreamService redisStreamService;
    private final Duration recheck;

    @Autowired
    public SessionStreamService(SharedSessionService sharedSessionService, SessionRunLock sessionRunLock, RedisStreamService redisStreamService) {
        this(sharedSessionService, sessionRunLock, redisStreamService, RECHECK);
    }

    SessionStreamService(SharedSessionService sharedSessionService, SessionRunLock sessionRunLock, RedisStreamService redisStreamService,
                         Duration recheck) {
        this.sharedSessionService = sharedSessionService;
        this.sessionRunLock = sessionRunLock;
        this.redisStreamService = redisStreamService;
        this.recheck = recheck;
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

        Flux<ServerSentEvent<?>> follow = redisStreamService.follow(chatId, streamUserId, readerUserId,
                cursor == null ? StreamEventId.BEGINNING : cursor);

        return SseResponse.ok(sharedSession.getMode() == SessionMode.CONVERSATION
                ? endWhenNotWelcome(follow, sessionId, readerUserId, sharedSession.getStatus().acceptsMessages())
                : follow);
    }

    /**
     * Participation is checked when a watch opens, but a conversation lets people leave: a former
     * participant must not go on reading what is said after they went. The follow ends, within the
     * recheck interval, once the reader is no longer seated, or once a session that was active when
     * they connected is not. A watch opened on a session that was already over is not ended for that,
     * or a client that reconnects on close would reconnect every interval.
     */
    private Flux<ServerSentEvent<?>> endWhenNotWelcome(Flux<ServerSentEvent<?>> follow, UUID sessionId, UUID readerUserId,
                                                       boolean activeAtConnect) {
        Flux<Long> unwelcome = Flux.interval(recheck, Schedulers.boundedElastic())
                .filter(_ -> !stillWelcome(sessionId, readerUserId, activeAtConnect))
                .take(1);

        return follow.takeUntilOther(unwelcome)
                .doOnComplete(() -> log.info("Ended the watch of shared session {}: the reader left or the session is over", sessionId));
    }

    private boolean stillWelcome(UUID sessionId, UUID readerUserId, boolean activeAtConnect) {
        try {
            SharedSession current = sharedSessionService.requireSeated(sessionId, readerUserId);

            return !activeAtConnect || current.getStatus().acceptsMessages();
        } catch (SessionException exception) {
            return false;
        } catch (RuntimeException exception) {
            // A lookup that failed says nothing about whether they left; the next one will.
            log.warn("Could not recheck the reader of shared session {}: {}", sessionId, exception.getClass().getSimpleName());
            return true;
        }
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
