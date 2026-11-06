package com.solesonic.task;

import com.solesonic.service.memory.MemoryEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Embeds whatever was not embedded when it was written: memories that predate embedding, those whose
 * embedding call failed, and every memory again after a change of embedding model. Drains at
 * {@code batch-size} memories per tick.
 */
@Component
public class MemoryEmbeddingTask {
    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingTask.class);

    private final MemoryEmbeddingService memoryEmbeddingService;

    public MemoryEmbeddingTask(MemoryEmbeddingService memoryEmbeddingService) {
        this.memoryEmbeddingService = memoryEmbeddingService;
    }

    @Scheduled(cron = "${solesonic.llm.memory.embedding.cron}")
    public void embed() {
        log.debug("Embedding stale memories");

        try {
            memoryEmbeddingService.embedStale();
        } catch (RuntimeException exception) {
            log.warn("Could not embed stale memories, retrying next tick ({})", exception.getClass().getSimpleName());
        }
    }
}
