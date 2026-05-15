package com.solesonic.config.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.model.prompt.SlashCommand;
import io.a2a.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.solesonic.config.a2a.A2AWebClientConfig.A2A_WEB_CLIENT;

@Component
@ConditionalOnProperty(prefix = "solesonic.a2a", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(A2AClientProperties.class)
public class A2AAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(A2AAgentRegistry.class);

    private final Map<String, AgentCard> agentCards = new LinkedHashMap<>();

    public A2AAgentRegistry(A2AClientProperties properties,
                            @Qualifier(A2A_WEB_CLIENT) WebClient webClient) {
        discoverAgents(properties, webClient);
    }

    private void discoverAgents(A2AClientProperties a2AClientProperties, WebClient webClient) {
        String baseUri = a2AClientProperties.getBaseUri();

        if (baseUri == null || baseUri.isBlank()) {
            log.warn("A2A is enabled but no base-uri is configured under solesonic.a2a.base-uri");
            return;
        }

        List<String> agentCardUris = fetchAgentCardUris(baseUri, webClient);

        if (agentCardUris.isEmpty()) {
            log.warn("No A2A agents discovered at {}/a2a/agents", baseUri);
            return;
        }

        for (String agentCardUri : agentCardUris) {
            try {
                log.info("Discovering A2A agent from card URI: {}", agentCardUri);

                AgentCard agentCard = fetchAgentCard(agentCardUri, webClient);

                agentCards.put(agentCard.name(), agentCard);

                log.info("Registered A2A agent '{}'", agentCard.name());
            } catch (Exception exception) {
                log.error("Failed to discover A2A agent from {}: {}", agentCardUri, exception.getMessage(), exception);
            }
        }

        log.info("A2A agent registry initialized with {} agent(s)", agentCards.size());
    }

    private List<String> fetchAgentCardUris(String baseUri, WebClient webClient) {
        try {
            List<String> agentCardUris = webClient.get()
                    .uri(baseUri + "/a2a/agents")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<String>>() {})
                    .block();

            return agentCardUris != null ? agentCardUris : List.of();
        } catch (Exception exception) {
            log.error("Failed to fetch agent card URIs from {}/a2a/agents: {}", baseUri, exception.getMessage(), exception);
            return List.of();
        }
    }

    private AgentCard fetchAgentCard(String agentCardUri, WebClient webClient) throws Exception {
        String json = webClient.get()
                .uri(agentCardUri)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return new ObjectMapper().readValue(json, AgentCard.class);
    }

    public AgentCard getCard(String agentName) {
        AgentCard agentCard = agentCards.get(agentName);

        if (agentCard == null) {
            throw new IllegalArgumentException("Unknown A2A agent: " + agentName);
        }

        return agentCard;
    }

    public boolean hasAgent(String agentName) {
        return agentCards.containsKey(agentName);
    }

    public List<SlashCommand> asSlashCommands() {
        List<SlashCommand> slashCommands = new ArrayList<>();

        for (AgentCard agentCard : agentCards.values()) {
            slashCommands.add(new SlashCommand(agentCard));
        }

        return slashCommands;
    }
}
