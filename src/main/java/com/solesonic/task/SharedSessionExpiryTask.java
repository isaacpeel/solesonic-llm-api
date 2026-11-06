package com.solesonic.task;

import com.solesonic.service.session.SessionInviteService;
import com.solesonic.service.session.SharedSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires invites nobody answered and shared sessions nobody wrote to. Counts only in the log.
 */
@Component
public class SharedSessionExpiryTask {
    private static final Logger log = LoggerFactory.getLogger(SharedSessionExpiryTask.class);

    private final SessionInviteService sessionInviteService;
    private final SharedSessionService sharedSessionService;

    public SharedSessionExpiryTask(SessionInviteService sessionInviteService, SharedSessionService sharedSessionService) {
        this.sessionInviteService = sessionInviteService;
        this.sharedSessionService = sharedSessionService;
    }

    @Scheduled(cron = "${solesonic.shared-session.expiry-cron}")
    public void expire() {
        int invites = sessionInviteService.expire();
        int sessions = sharedSessionService.expireIdle();

        if (invites > 0 || sessions > 0) {
            log.info("Expired {} shared-session invite(s) and {} idle session(s)", invites, sessions);
        }
    }
}
