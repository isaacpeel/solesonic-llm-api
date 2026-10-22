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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.solesonic.config.a2a.A2AWebClientConfig.A2A_WEB_CLIENT;
import static com.solesonic.config.a2a.LiteLlmAgentDiscovery.describe;

/**
 * Holds every A2A agent card, from two sources: the agent host, which must be reachable at startup, and
 * LiteLLM's agent gateway, which need not be. A LiteLLM agent never replaces an agent-host agent of the
 * same name.
 */
@Component
@EnableConfigurationProperties(A2AClientProperties.class)
public class A2AAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(A2AAgentRegistry.class);
    public static final String A2A = "a2a";
    public static final String AGENTS = "agents";

    private final Map<String, RegisteredAgent> agents = new LinkedHashMap<>();
    private final WebClient webClient;
    private final LiteLlmAgentDiscovery liteLlmAgentDiscovery;
    private final Duration timeout;

    public A2AAgentRegistry(@Qualifier(A2A_WEB_CLIENT) WebClient webClient,
                            LiteLlmAgentDiscovery liteLlmAgentDiscovery,
                            A2AClientProperties properties) {
        this.webClient = webClient;
        this.liteLlmAgentDiscovery = liteLlmAgentDiscovery;
        this.timeout = Duration.ofSeconds(properties.timeoutSeconds());
    }

    @PostConstruct
    void discoverAgents() {
        discoverAgentHostAgents();
        discoverLiteLlmAgents();

        if (MapUtils.isNotEmpty(agents)) {
            agents.keySet().forEach(agentKey -> log.info("Agent Found: {}", agents.get(agentKey).agentCard().description()));
        }

        log.info("A2A agent registry initialized with {} agent(s)", agents.size());
    }

    private void discoverAgentHostAgents() {
        List<String> agentCardUris = fetchAgentCardUris();

        agentCardUris.forEach(agentCardUri -> {
                log.debug("Discovering A2A agent from card URI: {}", agentCardUri);

                AgentCard agentCard = fetchAgentCard(agentCardUri);
                agents.put(agentCard.name(), new RegisteredAgent(agentCard, A2AAgentSource.AGENT_HOST));

                log.debug("Registered A2A agent name: '{}'", agentCard.name());
        });
    }

    private void discoverLiteLlmAgents() {
        List<AgentCard> agentCards;

        try {
            agentCards = liteLlmAgentDiscovery.discover();
        } catch (RuntimeException exception) {
            log.warn("LiteLLM A2A agent discovery failed ({}); continuing without LiteLLM agents", describe(exception));
            return;
        }

        for (AgentCard agentCard : agentCards) {
            RegisteredAgent existing = agents.get(agentCard.name());

            if (existing != null) {
                log.error("A2A agent '{}' is offered by both {} and {}; keeping the {} agent",
                        agentCard.name(), existing.source(), A2AAgentSource.LITELLM, existing.source());
                continue;
            }

            agents.put(agentCard.name(), new RegisteredAgent(agentCard, A2AAgentSource.LITELLM));

            log.debug("Registered LiteLLM A2A agent name: '{}'", agentCard.name());
        }
    }

    private List<String> fetchAgentCardUris() {
        log.debug("Getting agent card URIs.");

        return webClient.get()
                .uri(uriBuilder ->
                        uriBuilder.pathSegment(A2A)
                                .pathSegment(AGENTS)
                                .build()
                )
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<String>>() {})
                .timeout(timeout)
                .block();
    }

    private AgentCard fetchAgentCard(String agentCardUri) {
        return webClient.get()
                .uri(URI.create(agentCardUri))
                .retrieve()
                .bodyToMono(AgentCard.class)
                .timeout(timeout)
                .block();
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
