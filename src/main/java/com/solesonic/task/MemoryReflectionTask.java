package com.solesonic.task;

import com.solesonic.service.memory.MemoryReflectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Learns from conversations that have gone quiet, off the request path, so no model call is added to
 * a live turn. On first deploy every existing conversation is eligible, which is the whole of the
 * retroactive backfill; it drains at {@code batch-size} chats per tick.
 */
@Component
@ConditionalOnProperty(name = "solesonic.llm.memory.reflection.enabled", havingValue = "true")
public class MemoryReflectionTask {
    private static final Logger log = LoggerFactory.getLogger(MemoryReflectionTask.class);

    private final MemoryReflectionService memoryReflectionService;

    public MemoryReflectionTask(MemoryReflectionService memoryReflectionService) {
        this.memoryReflectionService = memoryReflectionService;
    }

    @Scheduled(cron = "${solesonic.llm.memory.reflection.cron}")
    public void reflect() {
        log.debug("Reflecting on idle conversations");

        memoryReflectionService.reflectEligible();
    }
}
