package com.solesonic.service.a2a;

import com.solesonic.config.a2a.A2AClientProperties;
import com.solesonic.model.a2a.A2ASound;
import com.solesonic.model.chat.SoundCue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class A2ASoundServiceTest {
    private static final Duration TTL = Duration.ofDays(7);
    private static final byte[] CLIP = "ID3-fake-mp3".getBytes(StandardCharsets.UTF_8);
    private static final String CLIP_ID = "3c1b5f9b0ac0c1d4c6d2b6e0b2b0e4c5c6d0e7f2a9b8c7d6e5f4a3b2c1d0e9f8";

    @Mock
    private ReactiveRedisTemplate<String, byte[]> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, byte[]> valueOperations;

    private A2ASoundService soundService;

    @BeforeEach
    void setUp() {
        A2AClientProperties properties = new A2AClientProperties(false, 5, "http://agents",
                new A2AClientProperties.Litellm("http://litellm:4000", "sk-izzybot"),
                new A2AClientProperties.Sound(DataSize.ofMegabytes(5), TTL));

        soundService = new A2ASoundService(redisTemplate, properties, "/izzybot");
    }

    private static A2ASound sound(String cueId) {
        return new A2ASound(cueId, CLIP, "audio/mpeg", "theme", true);
    }

    @Test
    void theCueNamesTheClipByItsHashAndTheCueByItsArtifact() {
        SoundCue first = soundService.cue(sound("sound-1"));
        SoundCue second = soundService.cue(sound("sound-2"));

        assertThat(first.soundId()).matches("[0-9a-f]{64}").isEqualTo(second.soundId());
        assertThat(first.cueId()).isEqualTo("sound-1");
        assertThat(second.cueId()).isEqualTo("sound-2");
        assertThat(first.cue()).isEqualTo("theme");
        assertThat(first.playOnce()).isTrue();
        assertThat(first.source()).isEqualTo(new SoundCue.PartSource("url", "/izzybot/sounds/" + first.soundId(), "audio/mpeg"));
    }

    @Test
    void aClipIsWrittenWithItsTypeAndAFreshExpiryOnEveryCue() {
        String soundId = soundService.cue(sound("sound-1")).soundId();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.set(anyString(), any(), eq(TTL))).thenReturn(Mono.just(true));

        StepVerifier.create(soundService.store(soundId, sound("sound-1"))).verifyComplete();
        StepVerifier.create(soundService.store(soundId, sound("sound-2"))).verifyComplete();

        verify(valueOperations, times(2)).set("a2a:sound:" + soundId, CLIP, TTL);
        verify(valueOperations, times(2)).set("a2a:sound:" + soundId + ":type", "audio/mpeg".getBytes(StandardCharsets.UTF_8), TTL);
    }

    @Test
    void aStoredClipLoadsWithItsType() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("a2a:sound:" + CLIP_ID)).thenReturn(Mono.just(CLIP));
        when(valueOperations.get("a2a:sound:" + CLIP_ID + ":type")).thenReturn(Mono.just("audio/mpeg".getBytes(StandardCharsets.UTF_8)));

        StepVerifier.create(soundService.load(CLIP_ID))
                .assertNext(storedSound -> {
                    assertThat(storedSound.bytes()).isEqualTo(CLIP);
                    assertThat(storedSound.mimeType()).isEqualTo("audio/mpeg");
                })
                .verifyComplete();
    }

    @Test
    void aClipMissingItsTypeIsNotFound() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("a2a:sound:" + CLIP_ID)).thenReturn(Mono.just(CLIP));
        when(valueOperations.get("a2a:sound:" + CLIP_ID + ":type")).thenReturn(Mono.empty());

        StepVerifier.create(soundService.load(CLIP_ID)).verifyComplete();
    }

    @Test
    void aMalformedIdNeverReachesRedis() {
        StepVerifier.create(soundService.load("../../etc")).verifyComplete();
        StepVerifier.create(soundService.load(CLIP_ID.toUpperCase())).verifyComplete();
        StepVerifier.create(soundService.load(CLIP_ID + "0")).verifyComplete();

        verifyNoInteractions(redisTemplate);
    }
}
