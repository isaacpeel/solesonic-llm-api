package com.solesonic.task;

import com.solesonic.service.atlassian.ConfluenceTrainingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "confluence.training.task.enabled", havingValue = "true")
public class ConfluenceSchedulingTask {
    private static final Logger log = LoggerFactory.getLogger(ConfluenceSchedulingTask.class);

    private final ConfluenceTrainingService confluenceTrainingService;

    public ConfluenceSchedulingTask(ConfluenceTrainingService confluenceTrainingService) {
        this.confluenceTrainingService = confluenceTrainingService;
    }

    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.MINUTES)
    public void scanConfluence() {
        log.debug("Scanning confluence...");
        confluenceTrainingService.pageScan();
    }
}
