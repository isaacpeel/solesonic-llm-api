package com.solesonic.config.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fails startup on shared-session configuration that would misbehave quietly rather than loudly. The
 * run lock has to outlast the longest A2A call a message can make: a lock that lapsed mid-run would let
 * a second message start a run on the same stream. The same goes for a conversation's run lock and the
 * chat model's timeout.
 */
@Configuration
public class SharedSessionConfig {
    private static final Logger log = LoggerFactory.getLogger(SharedSessionConfig.class);

    static final int MAX_NAME_CHARACTERS = 64;

    /**
     * What a shared conversation is meant to call: the default prompt's web-search tools and image generation.
     */
    private static final Set<String> EXPECTED_TOOLS = Set.of("web_search", "web_search_advanced", "web_search_news",
            "web_extract_content", "generate_image");

    /**
     * The configured tools that are not expected ones, for the startup warning.
     */
    static Set<String> unexpectedTools(Set<String> tools) {
        return tools.stream().filter(tool -> !EXPECTED_TOOLS.contains(tool)).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Bean
    public SharedSessionSettings sharedSessionSettings(@Value("${solesonic.shared-session.agents}") String agents,
                                                       @Value("${solesonic.shared-session.invite-ttl-hours}") long inviteTtlHours,
                                                       @Value("${solesonic.shared-session.max-pending-invites}") int maxPendingInvites,
                                                       @Value("${solesonic.shared-session.max-message-characters}") int maxMessageCharacters,
                                                       @Value("${solesonic.shared-session.max-name-characters}") int maxNameCharacters,
                                                       @Value("${solesonic.shared-session.run-lock-seconds}") long runLockSeconds,
                                                       @Value("${solesonic.shared-session.idle-days}") long idleDays,
                                                       @Value("${solesonic.a2a.timeout-seconds}") long a2aTimeoutSeconds,
                                                       @Value("${spring.ai.openai.chat.timeout}") Duration chatTimeout,
                                                       @Value("${solesonic.shared-session.conversation.enabled}") boolean conversationEnabled,
                                                       @Value("${solesonic.shared-session.conversation.max-participants}") int conversationMaxParticipants,
                                                       @Value("${solesonic.shared-session.conversation.max-message-characters}") int conversationMaxMessageCharacters,
                                                       @Value("${solesonic.shared-session.conversation.run-lock-seconds}") long conversationRunLockSeconds,
                                                       @Value("${solesonic.shared-session.conversation.tools}") String conversationTools,
                                                       @Value("${solesonic.shared-session.conversation.fork-max-messages}") int conversationForkMaxMessages,
                                                       @Value("${solesonic.shared-session.conversation.idle-days}") long conversationIdleDays,
                                                       @Value("${solesonic.shared-session.conversation.max-active-sessions}") int conversationMaxActiveSessions,
                                                       @Value("${solesonic.shared-session.conversation.commands}") String conversationCommands) {

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

        if (conversationMaxParticipants < 2 || conversationMaxMessageCharacters < 1 || conversationForkMaxMessages < 1) {
            throw new IllegalStateException("SHARED_CONVERSATION_MAX_PARTICIPANTS must be at least 2, and "
                    + "SHARED_CONVERSATION_MAX_MESSAGE_CHARACTERS and SHARED_CONVERSATION_FORK_MAX_MESSAGES at least 1");
        }

        if (conversationIdleDays < 1) {
            throw new IllegalStateException("SHARED_CONVERSATION_IDLE_DAYS must be at least 1");
        }

        if (conversationMaxActiveSessions < 1) {
            throw new IllegalStateException("SHARED_CONVERSATION_MAX_ACTIVE_SESSIONS must be at least 1");
        }

        if (conversationRunLockSeconds <= chatTimeout.toSeconds()) {
            throw new IllegalStateException("SHARED_CONVERSATION_RUN_LOCK_SECONDS must be greater than spring.ai.openai.chat.timeout ("
                    + chatTimeout.toSeconds() + "s), or a conversation run could outlive its lock");
        }

        Set<String> tools = Arrays.stream(conversationTools.split(","))
                .map(String::strip)
                .filter(tool -> !tool.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String tool : unexpectedTools(tools)) {
            log.warn("SHARED_CONVERSATION_TOOLS lists '{}', which is not a web-search tool or generate_image. Tools run as the "
                    + "sender, so a personal tool would expose the sender's data to the whole group.", tool);
        }

        Set<String> commands = Arrays.stream(conversationCommands.split(","))
                .map(String::strip)
                .filter(command -> !command.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        //Only the names that are always tools can be told apart here; the catalogue decides the rest at startup
        //(SharedCommandPolicy), and every send is checked again.
        Set<String> commandsWithoutTool = commands.stream()
                .filter(EXPECTED_TOOLS::contains)
                .filter(command -> !tools.contains(command))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        //A setting that is wrong is not worth a failed boot of the whole API: say so loudly and run without
        //the commands that could never have worked.
        if (!commandsWithoutTool.isEmpty()) {
            log.error("SHARED_CONVERSATION_COMMANDS lists {}, whose tool is not in SHARED_CONVERSATION_TOOLS. A tool command only "
                    + "runs when its tool is allowlisted too, so these commands are dropped from the allowlist", commandsWithoutTool);

            commands.removeAll(commandsWithoutTool);
        }

        SharedSessionSettings.Conversation conversation = new SharedSessionSettings.Conversation(conversationEnabled,
                conversationMaxParticipants,
                conversationMaxMessageCharacters,
                Duration.ofSeconds(conversationRunLockSeconds),
                Collections.unmodifiableSet(tools),
                conversationForkMaxMessages,
                Duration.ofDays(conversationIdleDays),
                conversationMaxActiveSessions,
                Collections.unmodifiableSet(commands));

        return new SharedSessionSettings(agentNames,
                Duration.ofHours(inviteTtlHours),
                maxPendingInvites,
                maxMessageCharacters,
                maxNameCharacters,
                Duration.ofSeconds(runLockSeconds),
                Duration.ofDays(idleDays),
                conversation);
    }
}
