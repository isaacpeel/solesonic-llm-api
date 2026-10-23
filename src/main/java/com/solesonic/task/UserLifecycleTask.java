package com.solesonic.task;

import com.solesonic.service.user.UserLifecycleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "solesonic.user.lifecycle.enabled", havingValue = "true")
public class UserLifecycleTask {
    private final UserLifecycleService userLifecycleService;

    public UserLifecycleTask(UserLifecycleService userLifecycleService) {
        this.userLifecycleService = userLifecycleService;
    }

    @Scheduled(cron = "${solesonic.user.lifecycle.cron}")
    public void sweep() {
        userLifecycleService.sweep();
    }
}
