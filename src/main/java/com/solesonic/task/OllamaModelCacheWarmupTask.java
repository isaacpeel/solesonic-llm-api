package com.solesonic.task;

import com.solesonic.service.ollama.OllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Populates the Ollama model cache once, at startup, so a fresh deploy does not leave every model
 * lookup to pay a cache miss. Cached entries have no TTL: past this, the only refresh is the manual
 * {@code POST /ollama/models/refresh} endpoint or a model record save/update.
 * <p>
 * Off the startup thread, like other {@link ApplicationReadyEvent} listeners that make outbound
 * calls: the refresh is one HTTP call per installed model, and an Ollama server that is slow or
 * unreachable would otherwise hold up readiness for the length of every one of those timeouts.
 * <p>
 * The failure is swallowed here rather than in {@link OllamaService#refreshCache()}, which
 * deliberately propagates so that an admin asking for a refresh is not answered with a silent
 * success. A down Ollama server at boot just leaves the cache empty, and it fills lazily on first
 * read exactly as it did before this ran.
 */
@Component
public class OllamaModelCacheWarmupTask {
    private static final Logger log = LoggerFactory.getLogger(OllamaModelCacheWarmupTask.class);

    private final OllamaService ollamaService;

    public OllamaModelCacheWarmupTask(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmCacheOnStartup() {
        CompletableFuture.runAsync(this::warmCache);
    }

    void warmCache() {
        log.info("Warming Ollama model cache on startup");

        try {
            ollamaService.refreshCache();
        } catch (Exception exception) {
            log.warn("Failed to warm Ollama model cache on startup: {}", exception.getMessage());
        }
    }
}
