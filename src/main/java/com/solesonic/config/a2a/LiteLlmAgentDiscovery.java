package com.solesonic.config.a2a;

import com.solesonic.model.a2a.LiteLlmAgent;
import com.solesonic.util.logging.Redactor;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.solesonic.config.a2a.LiteLlmA2AWebClientConfig.LITELLM_A2A_WEB_CLIENT;

/**
 * Discovers the agents the virtual key can reach through LiteLLM's agent gateway. Every card is pinned
 * to the gateway: only the path of each interface URL is kept, it must sit under the gateway's agent
 * routes, and the scheme and host always come from the configured gateway. A card mis-curated in
 * LiteLLM therefore cannot send the key and users' messages to an arbitrary URL, and a card written
 * with the scheme LiteLLM was reached on behind a TLS-terminating proxy still resolves to the gateway.
 * <p>
 * The command is the {@code agent_name} the card was fetched by, because LiteLLM serves the agent's
 * display name as the card's name.
 */
@Component
public class LiteLlmAgentDiscovery {

    private static final Logger log = LoggerFactory.getLogger(LiteLlmAgentDiscovery.class);

    private static final String AGENT_ROUTES = "/a2a/";
    private static final List<String> ENCODED_PATH_SEPARATORS = List.of("%2e", "%2f", "%5c");
    private static final Pattern COMMAND_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final List<String> SECURITY_FIELDS = List.of("securitySchemes", "security", "securityRequirements");
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    /**
     * Discovery runs at startup, which an unreachable LiteLLM must not hold up for the turn timeout.
     */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final String gatewayOrigin;
    private final String gatewayAgentRoutes;

    public LiteLlmAgentDiscovery(@Qualifier(LITELLM_A2A_WEB_CLIENT) WebClient webClient,
                                 A2AClientProperties properties) {
        URI gatewayUri = URI.create(properties.litellm().baseUri()).normalize();
        String gatewayPath = Strings.CS.removeEnd(gatewayUri.getRawPath() == null ? "" : gatewayUri.getRawPath(), "/");

        this.webClient = webClient;
        this.gatewayOrigin = gatewayUri.getScheme() + "://" + gatewayUri.getRawAuthority();
        this.gatewayAgentRoutes = gatewayPath + AGENT_ROUTES;
    }

    /**
     * @throws RuntimeException when the agent list itself cannot be fetched. A single card that cannot
     *                          be fetched, or names an endpoint outside the gateway's agent routes, is
     *                          skipped instead.
     */
    public List<AgentCard> discover() {
        List<LiteLlmAgent> agents = fetchAgents();

        log.debug("LiteLLM lists {} agent(s)", agents.size());

        return agents.stream()
                .filter(LiteLlmAgentDiscovery::hasCommandName)
                .flatMap(agent -> fetchAgentCard(agent)
                        .flatMap(agentCard -> pinToGateway(agent.agentName(), agentCard))
                        .stream())
                .toList();
    }

    private List<LiteLlmAgent> fetchAgents() {
        List<LiteLlmAgent> agents = webClient.get()
                .uri(uriBuilder -> uriBuilder.pathSegment("v1", "agents").build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<LiteLlmAgent>>() {})
                .timeout(DISCOVERY_TIMEOUT)
                .block();

        return agents == null ? List.of() : agents;
    }

    private static boolean hasCommandName(LiteLlmAgent agent) {
        if (agent.agentName() == null || !COMMAND_NAME.matcher(agent.agentName()).matches()) {
            log.warn("Skipping a LiteLLM agent whose agent_name is missing or not a valid command name");
            return false;
        }

        return true;
    }

    private Optional<AgentCard> fetchAgentCard(LiteLlmAgent agent) {
        try {
            String body = webClient.get()
                    .uri(uriBuilder -> uriBuilder.pathSegment("a2a", "{agentName}", ".well-known", "agent-card.json")
                            .build(agent.agentName()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(DISCOVERY_TIMEOUT)
                    .block();

            if (StringUtils.isBlank(body)) {
                log.warn("Skipping LiteLLM agent '{}': its card is empty", agent.agentName());
                return Optional.empty();
            }

            return Optional.of(parseAgentCard(body));
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("Skipping LiteLLM agent '{}': its card could not be fetched ({})", agent.agentName(), describe(exception));
            return Optional.empty();
        }
    }

    /**
     * Parsed the way the SDK's own {@code A2ACardResolver} parses a card: through its protobuf JSON
     * form, ignoring fields it does not recognise. The security fields are removed first: LiteLLM writes
     * them in the A2A 0.3 shape, which the SDK rejects, and they only describe authenticating to LiteLLM,
     * which the virtual key header already does.
     */
    private static AgentCard parseAgentCard(String body) throws JsonProcessingException {
        JsonNode card = JSON_MAPPER.readTree(body);

        if (card instanceof ObjectNode cardFields) {
            cardFields.remove(SECURITY_FIELDS);
        }

        org.a2aproject.sdk.grpc.AgentCard.Builder agentCardBuilder = org.a2aproject.sdk.grpc.AgentCard.newBuilder();
        JSONRPCUtils.parseJsonString(JSON_MAPPER.writeValueAsString(card), agentCardBuilder, "", true);

        return ProtoUtils.FromProto.agentCard(agentCardBuilder);
    }

    /**
     * The legacy {@code url} and {@code additionalInterfaces} are dropped rather than pinned: the client
     * resolves its endpoint from {@code supportedInterfaces} alone.
     */
    private Optional<AgentCard> pinToGateway(String agentName, AgentCard agentCard) {
        if (agentCard.supportedInterfaces().isEmpty()) {
            log.error("Refusing LiteLLM agent '{}': its card declares no interface", agentName);
            return Optional.empty();
        }

        List<AgentInterface> pinnedInterfaces = new ArrayList<>();

        for (AgentInterface agentInterface : agentCard.supportedInterfaces()) {
            Optional<String> gatewayUrl = onGateway(agentInterface.url());

            if (gatewayUrl.isEmpty()) {
                log.error("Refusing LiteLLM agent '{}': its card points at {}, which is not under the gateway's agent routes {}",
                        agentName, Redactor.sanitizePath(agentInterface.url()), gatewayAgentRoutes);
                return Optional.empty();
            }

            pinnedInterfaces.add(new AgentInterface(agentInterface.protocolBinding(), gatewayUrl.get(),
                    agentInterface.tenant(), agentInterface.protocolVersion()));
        }

        return Optional.of(AgentCard.builder(agentCard)
                .name(agentName)
                .supportedInterfaces(pinnedInterfaces)
                .build());
    }

    /**
     * An encoded dot or separator is refused outright: {@link URI#normalize()} cannot see through it,
     * but the server it reaches may decode it into a traversal out of the gateway's agent routes.
     */
    private Optional<String> onGateway(@Nullable String endpoint) {
        if (StringUtils.isBlank(endpoint)) {
            return Optional.empty();
        }

        String path;

        try {
            path = new URI(endpoint).normalize().getRawPath();
        } catch (URISyntaxException exception) {
            return Optional.empty();
        }

        if (path == null || !path.startsWith(gatewayAgentRoutes)) {
            return Optional.empty();
        }

        String lowerCasePath = path.toLowerCase(Locale.ROOT);

        if (ENCODED_PATH_SEPARATORS.stream().anyMatch(lowerCasePath::contains)) {
            return Optional.empty();
        }

        return Optional.of(gatewayOrigin + path);
    }

    static String describe(Throwable exception) {
        if (exception instanceof WebClientResponseException webClientResponseException) {
            return "HTTP " + webClientResponseException.getStatusCode().value();
        }

        return exception.getClass().getSimpleName();
    }
}
