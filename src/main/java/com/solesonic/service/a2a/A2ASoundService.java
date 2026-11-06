package com.solesonic.service.a2a;

import com.solesonic.config.a2a.A2AClientProperties;
import com.solesonic.model.a2a.A2ASound;
import com.solesonic.model.chat.SoundCue;
import com.solesonic.redis.config.RedisStreamConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Keeps the sounds agents send, content-addressed by the SHA-256 of their bytes, so a clip played in
 * every game is stored once. Only the cue travels on the chat stream; the bytes are fetched from
 * {@code /sounds/{soundId}}.
 */
@Service
public class A2ASoundService {
    private static final String SHA_256 = "SHA-256";
    private static final String KEY_PREFIX = "a2a:sound:";
    private static final String TYPE_SUFFIX = ":type";
    private static final String SOUNDS_PATH = "/sounds/";
    private static final Pattern SOUND_ID = Pattern.compile("[0-9a-f]{64}");

    private final ReactiveRedisTemplate<String, byte[]> redisTemplate;
    private final Duration ttl;
    private final String contextPath;

    public A2ASoundService(@Qualifier(RedisStreamConfig.REACTIVE_BYTES_REDIS_TEMPLATE) ReactiveRedisTemplate<String, byte[]> redisTemplate,
                           A2AClientProperties properties,
                           @Value("${server.servlet.context-path}") String contextPath) {
        this.redisTemplate = redisTemplate;
        this.ttl = properties.sound().ttl();
        this.contextPath = contextPath;
    }

    /**
     * Synchronous and free of I/O, so the cue is ready before the reply's first chunk can arrive.
     */
    public SoundCue cue(A2ASound sound) {
        String soundId = HexFormat.of().formatHex(sha256(sound.bytes()));

        return new SoundCue(sound.cueId(), soundId, sound.cue(), sound.playOnce(),
                new SoundCue.PartSource(SoundCue.URL_SOURCE, contextPath + SOUNDS_PATH + soundId, sound.mimeType()));
    }

    /**
     * Written on every cue, even when the clip is already stored, so a cue issued now never outlives
     * the clip it points at. Renewing with {@code EXPIRE} instead would race the key expiring.
     */
    public Mono<Void> store(String soundId, A2ASound sound) {
        String bytesKey = KEY_PREFIX + soundId;
        ReactiveValueOperations<String, byte[]> valueOperations = redisTemplate.opsForValue();

        return Mono.when(valueOperations.set(bytesKey, sound.bytes(), ttl),
                valueOperations.set(bytesKey + TYPE_SUFFIX, sound.mimeType().getBytes(StandardCharsets.UTF_8), ttl));
    }

    /**
     * Empty for an id that is not a SHA-256 in lower-case hex, which never reaches Redis.
     */
    public Mono<StoredSound> load(String soundId) {
        if (soundId == null || !SOUND_ID.matcher(soundId).matches()) {
            return Mono.empty();
        }

        String bytesKey = KEY_PREFIX + soundId;
        ReactiveValueOperations<String, byte[]> valueOperations = redisTemplate.opsForValue();

        return Mono.zip(valueOperations.get(bytesKey), valueOperations.get(bytesKey + TYPE_SUFFIX))
                .map(stored -> new StoredSound(stored.getT1(), new String(stored.getT2(), StandardCharsets.UTF_8)));
    }

    /**
     * SHA-256 is mandated by the platform, so its absence is a broken JVM rather than a runtime
     * condition to handle.
     */
    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance(SHA_256).digest(bytes);
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            throw new IllegalStateException("SHA-256 is not available", noSuchAlgorithmException);
        }
    }

    public record StoredSound(byte[] bytes, String mimeType) {
    }
}
