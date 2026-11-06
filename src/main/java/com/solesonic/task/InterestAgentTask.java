package com.solesonic.task;

import com.solesonic.service.interest.NewsAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs due news agents off the request path, then prunes the seen items past retention.
 * {@code solesonic.interest.news.enabled=false} is the kill switch for every agent at once.
 */
@Component
@ConditionalOnProperty(name = "solesonic.interest.news.enabled", havingValue = "true")
public class InterestAgentTask {
    private static final Logger log = LoggerFactory.getLogger(InterestAgentTask.class);

    private final NewsAgentService newsAgentService;

    public InterestAgentTask(NewsAgentService newsAgentService) {
        this.newsAgentService = newsAgentService;
    }

    @Scheduled(cron = "${solesonic.interest.news.cron}")
    public void run() {
        log.debug("Running due news agents");

        newsAgentService.runDue();
    }
}
