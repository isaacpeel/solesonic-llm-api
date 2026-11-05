package com.solesonic.config.session;

import com.solesonic.model.session.SessionMode;

import java.time.Duration;
import java.util.Set;

/**
 * Shared-session configuration, validated once at startup by {@link SharedSessionConfig}. The expiry
 * cron is read by the task itself.
 * <p>
 * {@code runLock} and the message limit above are the AGENT mode's; {@link Conversation} carries the
 * CONVERSATION mode's. Use {@link #runLock(SessionMode)} where the mode is known.
 *
 * @param agentNames the A2A agents a session may be opened with
 */
public record SharedSessionSettings(Set<String> agentNames,
                                    Duration inviteTimeToLive,
                                    int maxPendingInvites,
                                    int maxMessageCharacters,
                                    int maxNameCharacters,
                                    Duration runLock,
                                    Duration idle,
                                    Conversation conversation) {

    /**
     * For callers that only know the agent mode: conversation mode takes {@link Conversation#defaults()}.
     */
    public SharedSessionSettings(Set<String> agentNames,
                                 Duration inviteTimeToLive,
                                 int maxPendingInvites,
                                 int maxMessageCharacters,
                                 int maxNameCharacters,
                                 Duration runLock,
                                 Duration idle) {
        this(agentNames, inviteTimeToLive, maxPendingInvites, maxMessageCharacters, maxNameCharacters, runLock, idle,
                Conversation.defaults());
    }

    public boolean allowsAgent(String agentName) {
        return agentName != null && agentNames.contains(agentName);
    }

    /**
     * How long one run holds a session's run lock: the longest a message in that mode can take.
     */
    public Duration runLock(SessionMode mode) {
        return mode == SessionMode.CONVERSATION ? conversation.runLock() : runLock;
    }

    /**
     * @param enabled              whether conversations can be shared at all
     * @param maxParticipants      seated people plus pending invitees, the owner included
     * @param maxMessageCharacters the length limit of one message
     * @param runLock              outlasts the chat model's timeout
     * @param tools                the MCP tools a shared conversation may call
     * @param forkMaxMessages      the most recent messages copied when a chat is shared
     * @param idle                 how long a conversation may go unwritten before it expires; a chat may sit
     *                             idle far longer than a game
     * @param maxActiveSessions    the most ACTIVE conversations one user may own
     */
    public record Conversation(boolean enabled,
                               int maxParticipants,
                               int maxMessageCharacters,
                               Duration runLock,
                               Set<String> tools,
                               int forkMaxMessages,
                               Duration idle,
                               int maxActiveSessions) {

        /**
         * With the default idle window (90 days) and active-session cap (20).
         */
        public Conversation(boolean enabled, int maxParticipants, int maxMessageCharacters, Duration runLock,
                            Set<String> tools, int forkMaxMessages) {
            this(enabled, maxParticipants, maxMessageCharacters, runLock, tools, forkMaxMessages, Duration.ofDays(90), 20);
        }

        static Conversation defaults() {
            return new Conversation(true, 8, 8000, Duration.ofSeconds(2100),
                    Set.of("web_search", "web_search_advanced", "web_search_news", "web_extract_content", "generate_image"),
                    500);
        }
    }
}
