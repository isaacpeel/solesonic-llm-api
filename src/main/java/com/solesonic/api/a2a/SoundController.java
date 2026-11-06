package com.solesonic.api.a2a;

import com.solesonic.service.a2a.A2ASoundService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Serves the sounds agents send. Open to any signed-in user: a sound is the same for everyone, and
 * its id is learned only from a stream the caller could already read. The id is the hash of the
 * bytes, so what it names never changes.
 */
@RestController
@RequestMapping("/sounds")
public class SoundController {

    private final A2ASoundService soundService;

    public SoundController(A2ASoundService soundService) {
        this.soundService = soundService;
    }

    @GetMapping("/{soundId}")
    public Mono<ResponseEntity<byte[]>> sound(@PathVariable String soundId) {
        return soundService.load(soundId)
                .map(storedSound -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(storedSound.mimeType()))
                        .eTag("\"" + soundId + "\"")
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                        .body(storedSound.bytes()))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
