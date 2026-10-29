package com.solesonic.config.session;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fails startup on shared-session configuration that would misbehave quietly rather than loudly. The
 * run lock has to outlast the longest A2A call a message can make: a lock that lapsed mid-run would let
 * a second message start a run on the same stream.
 */
@Configuration
public class SharedSessionConfig {
    static final int MAX_NAME_CHARACTERS = 64;

    @Bean
    public SharedSessionSettings sharedSessionSettings(@Value("${solesonic.shared-session.agents}") String agents,
                                                       @Value("${solesonic.shared-session.invite-ttl-hours}") long inviteTtlHours,
                                                       @Value("${solesonic.shared-session.max-pending-invites}") int maxPendingInvites,
                                                       @Value("${solesonic.shared-session.max-message-characters}") int maxMessageCharacters,
                                                       @Value("${solesonic.shared-session.max-name-characters}") int maxNameCharacters,
                                                       @Value("${solesonic.shared-session.run-lock-seconds}") long runLockSeconds,
                                                       @Value("${solesonic.shared-session.idle-days}") long idleDays,
                                                       @Value("${solesonic.a2a.timeout-seconds}") long a2aTimeoutSeconds) {

        Set<String> agentNames = Arrays.stream(agents.split(","))
                .map(String::strip)
                .filter(agentName -> !agentName.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        if (agentNames.isEmpty()) {
            throw new IllegalStateException("SHARED_SESSION_AGENTS must name at least one A2A agent");
        }

        if (inviteTtlHours < 1 || maxPendingInvites < 1 || maxMessageCharacters < 1 || idleDays < 1) {
            throw new IllegalStateException("SHARED_SESSION_INVITE_TTL_HOURS, SHARED_SESSION_MAX_PENDING_INVITES, "
                    + "SHARED_SESSION_MAX_MESSAGE_CHARACTERS and SHARED_SESSION_IDLE_DAYS must be at least 1");
        }

        if (maxNameCharacters < 1 || maxNameCharacters > MAX_NAME_CHARACTERS) {
            throw new IllegalStateException("SHARED_SESSION_MAX_NAME_CHARACTERS must be between 1 and " + MAX_NAME_CHARACTERS);
        }

        if (runLockSeconds <= a2aTimeoutSeconds) {
            throw new IllegalStateException("SHARED_SESSION_RUN_LOCK_SECONDS must be greater than solesonic.a2a.timeout-seconds ("
                    + a2aTimeoutSeconds + "), or a message could outlive its lock");
        }

        return new SharedSessionSettings(agentNames,
                Duration.ofHours(inviteTtlHours),
                maxPendingInvites,
                maxMessageCharacters,
                maxNameCharacters,
                Duration.ofSeconds(runLockSeconds),
                Duration.ofDays(idleDays));
    }
}
