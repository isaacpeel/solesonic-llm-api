package com.solesonic.config.a2a;

import com.solesonic.model.a2a.A2AAgentSource;
import com.solesonic.model.a2a.RegisteredAgent;
import com.solesonic.model.prompt.AgentSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import jakarta.annotation.PostConstruct;
import org.a2aproject.sdk.spec.AgentCard;
import org.apache.commons.collections4.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Holds every A2A agent card discovered through LiteLLM's agent gateway, which need not be reachable at
 * startup.
 */
@Component
@EnableConfigurationProperties(A2AClientProperties.class)
public class A2AAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(A2AAgentRegistry.class);

    private final Map<String, RegisteredAgent> agents = new LinkedHashMap<>();
    private final LiteLlmAgentDiscovery liteLlmAgentDiscovery;

    public A2AAgentRegistry(LiteLlmAgentDiscovery liteLlmAgentDiscovery) {
        this.liteLlmAgentDiscovery = liteLlmAgentDiscovery;
    }

    @PostConstruct
    void discoverAgents() {
        discoverLiteLlmAgents();

        if (MapUtils.isNotEmpty(agents)) {
            agents.keySet().forEach(agentKey -> {
                RegisteredAgent registeredAgent = agents.get(agentKey);
                String agentName = registeredAgent.agentCard().name();
                String agentUrl = registeredAgent.agentCard().url();

                log.info("Agent Found: {} ({})", agentName, agentUrl);
            });
        }

        log.info("A2A agent registry initialized with {} agent(s)", agents.size());
    }

    private void discoverLiteLlmAgents() {
        List<AgentCard> agentCards;

        try {
            agentCards = liteLlmAgentDiscovery.discover();
        } catch (RuntimeException exception) {
            log.error("LiteLLM A2A agent discovery failed: ", exception);
            return;
        }

        for (AgentCard agentCard : agentCards) {
            agents.put(agentCard.name(), new RegisteredAgent(agentCard, A2AAgentSource.LITELLM));

            log.debug("Registered LiteLLM A2A agent name: '{}'", agentCard.name());
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
