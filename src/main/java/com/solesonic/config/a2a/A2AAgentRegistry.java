package com.solesonic.config.a2a;

import com.solesonic.model.a2a.A2AAgentSource;
import com.solesonic.model.a2a.RegisteredAgent;
import com.solesonic.model.prompt.AgentSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import jakarta.annotation.PostConstruct;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Holds every A2A agent card discovered through LiteLLM's agent gateway, which need not be reachable at
 * startup.
 * <p>
 * The LiteLLM agents are discovered again every {@code solesonic.a2a.litellm.refresh-interval}, so an
 * agent added to or removed from LiteLLM is offered or withdrawn without a restart. Each refresh replaces
 * the whole set at once, so a reader sees either the old set or the new one. A refresh that cannot reach
 * LiteLLM keeps the last known agents rather than withdrawing them all over a transient outage.
 */
@Component
@EnableConfigurationProperties(A2AClientProperties.class)
public class A2AAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(A2AAgentRegistry.class);

    private final LiteLlmAgentDiscovery liteLlmAgentDiscovery;

    private volatile Map<String, RegisteredAgent> agents = Map.of();

    public A2AAgentRegistry(LiteLlmAgentDiscovery liteLlmAgentDiscovery) {
        this.liteLlmAgentDiscovery = liteLlmAgentDiscovery;
    }

    @PostConstruct
    void discoverAgents() {
        refreshLiteLlmAgents();

        agents.values().forEach(registeredAgent ->
                log.info("Agent Found: {} ({})", registeredAgent.agentCard().name(), registeredAgent.agentCard().url()));

        log.info("A2A agent registry initialized with {} agent(s)", agents.size());
    }

    @Scheduled(fixedDelayString = "${solesonic.a2a.litellm.refresh-interval:PT30S}",
            initialDelayString = "${solesonic.a2a.litellm.refresh-interval:PT30S}")
    public void refreshLiteLlmAgents() {
        List<AgentCard> agentCards;

        try {
            agentCards = liteLlmAgentDiscovery.discover();
        } catch (RuntimeException exception) {
            log.error("LiteLLM A2A agent discovery failed, keeping {} known agent(s): ", agents.size(), exception);
            return;
        }

        Map<String, RegisteredAgent> discovered = new LinkedHashMap<>();

        for (AgentCard agentCard : agentCards) {
            discovered.put(agentCard.name(), new RegisteredAgent(agentCard, A2AAgentSource.LITELLM));

            log.debug("Registered LiteLLM A2A agent name: '{}'", agentCard.name());
        }

        Map<String, RegisteredAgent> previous = agents;
        agents = Collections.unmodifiableMap(discovered);

        logChanges(previous.keySet(), discovered.keySet());
    }

    private static void logChanges(Set<String> previousNames, Set<String> currentNames) {
        Set<String> added = new TreeSet<>(currentNames);
        added.removeAll(previousNames);

        Set<String> removed = new TreeSet<>(previousNames);
        removed.removeAll(currentNames);

        if (!added.isEmpty()) {
            log.info("LiteLLM A2A agent(s) added: {}", added);
        }

        if (!removed.isEmpty()) {
            log.info("LiteLLM A2A agent(s) removed: {}", removed);
        }
    }

    public Optional<RegisteredAgent> agent(String agentName) {
        return Optional.ofNullable(agents.get(agentName));
    }

    public List<SlashCommand> asSlashCommands() {
        return agents.values()
                .stream()
                .map(RegisteredAgent::agentCard)
                .<SlashCommand>map(AgentSlashCommand::new)
                .toList();
    }
}
