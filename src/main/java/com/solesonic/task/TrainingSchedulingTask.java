package com.solesonic.task;

import com.solesonic.service.ollama.StatusHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "training.task.enabled", havingValue = "true")
public class TrainingSchedulingTask {
    private static final Logger log = LoggerFactory.getLogger(TrainingSchedulingTask.class);

    private final StatusHistoryService statusHistoryService;

    public TrainingSchedulingTask(StatusHistoryService statusHistoryService) {
        this.statusHistoryService = statusHistoryService;
    }

    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.SECONDS)
    public void embedDocuments() {
        log.debug("Looking for queued documents");
        statusHistoryService.processQueued();
    }
}
