package com.solesonic.config.a2a;

import com.solesonic.model.prompt.AgentSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import jakarta.annotation.PostConstruct;
import org.a2aproject.sdk.spec.AgentCard;
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

import static com.solesonic.config.a2a.A2AWebClientConfig.A2A_WEB_CLIENT;

@Component
@EnableConfigurationProperties(A2AClientProperties.class)
public class A2AAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(A2AAgentRegistry.class);
    public static final String A2A = "a2a";
    public static final String AGENTS = "agents";

    private final Map<String, AgentCard> agentCards = new LinkedHashMap<>();
    private final WebClient webClient;
    private final Duration timeout;

    public A2AAgentRegistry(@Qualifier(A2A_WEB_CLIENT) WebClient webClient,
                            A2AClientProperties properties) {
        this.webClient = webClient;
        this.timeout = Duration.ofSeconds(properties.timeoutSeconds());
    }

    @PostConstruct
    private void discoverAgents() {
        List<String> agentCardUris = fetchAgentCardUris();

        agentCardUris.forEach(agentCardUri -> {
                log.info("Discovering A2A agent from card URI: {}", agentCardUri);

                AgentCard agentCard = fetchAgentCard(agentCardUri);
                agentCards.put(agentCard.name(), agentCard);

                log.info("Registered A2A agent name: '{}'", agentCard.name());
        });

        log.info("A2A agent registry initialized with {} agent(s)", agentCards.size());
    }

    private List<String> fetchAgentCardUris() {
        log.info("Getting agent card URIs.");

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

    public AgentCard card(String agentName) {
        return agentCards.get(agentName);
    }

    @SuppressWarnings("unused")
    public boolean hasAgent(String agentName) {
        return agentCards.containsKey(agentName);
    }

    public List<SlashCommand> asSlashCommands() {
        return agentCards.values()
                .stream()
                .<SlashCommand>map(AgentSlashCommand::new)
                .toList();
    }
}
