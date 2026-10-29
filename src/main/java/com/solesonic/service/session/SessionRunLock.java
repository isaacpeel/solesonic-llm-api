package com.solesonic.service.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One run at a time per shared session. Every participant reads the same stream, and two runs on it at
 * once would interleave their frames into one unreadable message, so a message that finds a run in
 * progress is refused rather than queued.
 * <p>
 * The lock holds the run's id and is released by compare-and-delete, so a run that outlived its lock
 * can never release the lock of the run after it. Beside it sits the stream offset the run started
 * from, which is where a participant who opens the session mid-run starts reading.
 */
@Component
public class SessionRunLock {
    private static final Logger log = LoggerFactory.getLogger(SessionRunLock.class);

    private static final String LOCK_KEY_PREFIX = "shared-session:run:";
    private static final String CURSOR_KEY_PREFIX = "shared-session:run-cursor:";
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                redis.call('del', KEYS[2])
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final ReactiveStringRedisTemplate redisTemplate;

    public SessionRunLock(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean acquire(UUID sessionId, UUID runId, Duration timeToLive) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey(sessionId), runId.toString(), timeToLive)
                .block(LOOKUP_TIMEOUT);

        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Whether a run is in progress. A Redis failure answers no: the worst it costs is a client that
     * offers to send a message the server then refuses.
     */
    public boolean isHeld(UUID sessionId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(lockKey(sessionId)).block(LOOKUP_TIMEOUT));
        } catch (RuntimeException exception) {
            log.warn("Could not read the run lock of shared session {}: {}", sessionId, exception.getClass().getSimpleName());
            return false;
        }
    }

    public void recordCursor(UUID sessionId, String cursor, Duration timeToLive) {
        redisTemplate.opsForValue()
                .set(cursorKey(sessionId), cursor, timeToLive)
                .block(LOOKUP_TIMEOUT);
    }

    /**
     * The offset the run in progress started from, if one is.
     */
    public Optional<String> cursor(UUID sessionId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(cursorKey(sessionId)).block(LOOKUP_TIMEOUT));
    }

    /**
     * Never fails its caller, which is a run ending: a lock left behind lapses on its own.
     */
    public void release(UUID sessionId, UUID runId) {
        redisTemplate.execute(RELEASE, List.of(lockKey(sessionId), cursorKey(sessionId)), List.of(runId.toString()))
                .doOnError(exception -> log.warn("Could not release the run lock of shared session {}: {}", sessionId,
                        exception.getClass().getSimpleName()))
                .onErrorComplete()
                .subscribe();
    }

    private static String lockKey(UUID sessionId) {
        return LOCK_KEY_PREFIX + sessionId;
    }

    private static String cursorKey(UUID sessionId) {
        return CURSOR_KEY_PREFIX + sessionId;
    }
}
