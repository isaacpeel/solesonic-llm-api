package com.solesonic.task;

import com.solesonic.service.memory.MemoryRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Sweeps lapsed inferred memories one user at a time, each in its own transaction, so one user's
 * failure leaves everyone else's sweep standing.
 */
@Component
@ConditionalOnProperty(name = "solesonic.llm.memory.retention.enabled", havingValue = "true")
public class MemoryRetentionTask {
    private static final Logger log = LoggerFactory.getLogger(MemoryRetentionTask.class);

    private final MemoryRetentionService memoryRetentionService;

    public MemoryRetentionTask(MemoryRetentionService memoryRetentionService) {
        this.memoryRetentionService = memoryRetentionService;
    }

    @Scheduled(cron = "${solesonic.llm.memory.retention.cron}")
    public void retire() {
        int retired = 0;

        for (UUID userId : memoryRetentionService.usersWithInferredMemories()) {
            try {
                retired += memoryRetentionService.retire(userId);
            } catch (RuntimeException exception) {
                log.warn("Memory retention failed for user {} ({})", userId, exception.getClass().getSimpleName());
            }
        }

        log.info("Memory retention sweep retired {} memory(ies)", retired);
    }
}
