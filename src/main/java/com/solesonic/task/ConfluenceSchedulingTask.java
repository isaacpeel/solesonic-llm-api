package com.solesonic.task;

import com.solesonic.service.atlassian.ConfluenceIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "confluence.ingestion.task.enabled", havingValue = "true")
public class ConfluenceSchedulingTask {
    private static final Logger log = LoggerFactory.getLogger(ConfluenceSchedulingTask.class);

    private final ConfluenceIngestionService confluenceIngestionService;

    public ConfluenceSchedulingTask(ConfluenceIngestionService confluenceIngestionService) {
        this.confluenceIngestionService = confluenceIngestionService;
    }

    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.HOURS, initialDelay = 0)
    public void scanConfluence() {
        log.debug("Scanning confluence...");
        confluenceIngestionService.pageScan();
    }
}
