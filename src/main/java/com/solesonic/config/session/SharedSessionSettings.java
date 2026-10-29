package com.solesonic.config.session;

import java.time.Duration;
import java.util.Set;

/**
 * Shared-session configuration, validated once at startup by {@link SharedSessionConfig}. The expiry
 * cron is read by the task itself.
 *
 * @param agentNames the A2A agents a session may be opened with
 */
public record SharedSessionSettings(Set<String> agentNames,
                                    Duration inviteTimeToLive,
                                    int maxPendingInvites,
                                    int maxMessageCharacters,
                                    int maxNameCharacters,
                                    Duration runLock,
                                    Duration idle) {

    public boolean allowsAgent(String agentName) {
        return agentName != null && agentNames.contains(agentName);
    }
}
