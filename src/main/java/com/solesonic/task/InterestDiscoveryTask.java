package com.solesonic.task;

import com.solesonic.service.interest.InterestDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Offers news agents to users memory has just taught something, off the request path, so no model
 * call is added to a live turn. It also expires lapsed offers and withdraws ones whose memories are
 * gone.
 */
@Component
@ConditionalOnProperty(name = "solesonic.interest.discovery.enabled", havingValue = "true")
public class InterestDiscoveryTask {
    private static final Logger log = LoggerFactory.getLogger(InterestDiscoveryTask.class);

    private final InterestDiscoveryService interestDiscoveryService;

    public InterestDiscoveryTask(InterestDiscoveryService interestDiscoveryService) {
        this.interestDiscoveryService = interestDiscoveryService;
    }

    @Scheduled(cron = "${solesonic.interest.discovery.cron}")
    public void discover() {
        log.debug("Discovering interests");

        interestDiscoveryService.discoverEligible();
    }
}
