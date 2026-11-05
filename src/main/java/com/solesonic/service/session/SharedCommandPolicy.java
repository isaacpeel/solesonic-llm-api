package com.solesonic.service.session;

import com.solesonic.config.session.SharedSessionSettings;
import com.solesonic.exception.session.SessionException;
import com.solesonic.model.prompt.AgentSlashCommand;
import com.solesonic.model.prompt.LocalToolSlashCommand;
import com.solesonic.model.prompt.ModelSlashCommand;
import com.solesonic.model.prompt.PromptSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.model.prompt.ToolSlashCommand;
import com.solesonic.model.session.SessionErrorCode;
import com.solesonic.service.prompt.SlashCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which slash commands a shared conversation may run. Everything the sender types in one reaches the
 * whole room, and a command runs as the sender, so a command is allowed only when it is on
 * {@code conversation.commands} <em>and</em> its kind passes its own rule:
 * <ul>
 *   <li>tool commands (MCP and local): only when the tool is also on {@code conversation.tools}, so
 *       {@code /web_search} and {@code /generate_image} but never a personal tool;</li>
 *   <li>MCP prompt commands: only when listed, which by default none are. A prompt can call personal
 *       tools, so each one is added by hand after checking it;</li>
 *   <li>the model command: allowed, as it only changes the chat's model;</li>
 *   <li>A2A agent commands: never, because they run with the sender's identity and stay pinned.</li>
 * </ul>
 * A name that more than one catalogue entry carries must pass for every one of them, so a prompt or an
 * agent cannot borrow an allowed tool's name.
 */
@Service
public class SharedCommandPolicy {
    private static final Logger log = LoggerFactory.getLogger(SharedCommandPolicy.class);

    private final SlashCommandService slashCommandService;
    private final SharedSessionSettings sharedSessionSettings;

    /**
     * Listed commands the startup check found to be unusable, dropped from the effective allowlist.
     */
    private final Set<String> dropped = ConcurrentHashMap.newKeySet();

    public SharedCommandPolicy(SlashCommandService slashCommandService, SharedSessionSettings sharedSessionSettings) {
        this.slashCommandService = slashCommandService;
        this.sharedSessionSettings = sharedSessionSettings;
    }

    /**
     * @param commands the names a message asks to run, or null
     * @return the names, trimmed and without blanks, every one allowed
     * @throws SessionException {@code COMMAND_NOT_ALLOWED}, naming the first command refused
     */
    public Set<String> require(Set<String> commands) {
        Set<String> requested = new LinkedHashSet<>();

        if (commands != null) {
            commands.stream()
                    .filter(command -> command != null && !command.isBlank())
                    .map(String::strip)
                    .forEach(requested::add);
        }

        if (requested.isEmpty()) {
            return requested;
        }

        List<SlashCommand> catalogue = slashCommandService.slashCommands();

        for (String command : requested) {
            if (!allowed(command, catalogue)) {
                log.info("Refused command /{} in a shared conversation", command);

                throw new SessionException(SessionErrorCode.COMMAND_NOT_ALLOWED,
                        "The command /" + command + " is not available in a shared chat.");
            }
        }

        return requested;
    }

    /**
     * The slash catalogue a shared chat's composer offers: the usual list, narrowed to what
     * {@link #require} would accept.
     *
     * @param search the usual type-ahead search, or null for everything
     */
    public List<SlashCommand> catalogue(String search) {
        List<SlashCommand> catalogue = slashCommandService.slashCommands();

        return slashCommandService.typeAhead(search).stream()
                .filter(slashCommand -> allowed(slashCommand.command(), catalogue))
                .toList();
    }

    private boolean allowed(String command, List<SlashCommand> catalogue) {
        List<SlashCommand> named = catalogue.stream()
                .filter(slashCommand -> command.equals(slashCommand.command()))
                .toList();

        return !named.isEmpty() && named.stream().allMatch(slashCommand -> permits(command, slashCommand));
    }

    private boolean permits(String command, SlashCommand slashCommand) {
        SharedSessionSettings.Conversation conversation = sharedSessionSettings.conversation();

        if (!conversation.commands().contains(command) || dropped.contains(command)) {
            return false;
        }

        return switch (slashCommand) {
            case ToolSlashCommand _, LocalToolSlashCommand _ -> conversation.tools().contains(command);
            case PromptSlashCommand _, ModelSlashCommand _ -> true;
            case AgentSlashCommand _ -> false;
        };
    }

    /**
     * The listed commands the startup check dropped, for tests and diagnostics.
     */
    public Set<String> droppedCommands() {
        return Set.copyOf(dropped);
    }

    /**
     * Logs an ERROR for a listed tool command whose tool is not allowlisted, and drops it from the effective
     * allowlist: it would otherwise be refused on every send without anyone noticing the setting was wrong. It
     * never throws, because a wrong setting is not worth a failed boot. The configuration itself can only check
     * the names that are always tools; the catalogue knows the rest. An unreadable catalogue is not a reason to
     * stop either: every send is checked against it anyway.
     * <p>
     * Runs after {@link SlashCommandService#warmupCatalogOnStartup()}, which purges and reloads the catalogue
     * and so has to go first.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void verifyAgainstCatalogue() {
        try {
            check();
        } catch (RuntimeException exception) {
            log.warn("Could not check SHARED_CONVERSATION_COMMANDS against the slash catalogue: {}", exception.getMessage());
        }
    }

    private void check() {
        List<SlashCommand> catalogue = slashCommandService.slashCommands();

        SharedSessionSettings.Conversation conversation = sharedSessionSettings.conversation();
        Set<String> commandsWithoutTool = new LinkedHashSet<>();

        for (SlashCommand slashCommand : catalogue) {
            boolean tool = slashCommand instanceof ToolSlashCommand || slashCommand instanceof LocalToolSlashCommand;

            if (tool && conversation.commands().contains(slashCommand.command())
                    && !conversation.tools().contains(slashCommand.command())) {
                commandsWithoutTool.add(slashCommand.command());
            }

            if (slashCommand instanceof AgentSlashCommand && conversation.commands().contains(slashCommand.command())) {
                log.warn("SHARED_CONVERSATION_COMMANDS lists '{}', an agent command, which a shared chat always refuses",
                        slashCommand.command());
            }
        }

        if (!commandsWithoutTool.isEmpty()) {
            log.error("SHARED_CONVERSATION_COMMANDS lists the tool commands {}, whose tools are not in SHARED_CONVERSATION_TOOLS. "
                    + "A tool command only runs when its tool is allowlisted too, so these are dropped from the allowlist", commandsWithoutTool);

            dropped.addAll(commandsWithoutTool);
        }
    }
}
