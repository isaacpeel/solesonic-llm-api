package com.solesonic.config.a2a;

import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiteLlmAgentDiscoveryTest {

    private static final String GATEWAY = "http://litellm:4000";
    private static final String API_KEY = "sk-izzybot";
    private static final String AGENTS_PATH = "/v1/agents";
    private static final String LOGS_ROUTE = "/a2a/56c732c5-b908-4756-800e-5fff4b81719b";
    private static final String NETWORK_ROUTE = "/a2a/22932c7f-2ccb-4bf4-a54e-bbc27823485b";
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final List<ClientRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, StubbedResponse> responses = new HashMap<>();

    private ArrayNode agents;

    private record StubbedResponse(HttpStatus status, String body) {
    }

    @BeforeEach
    void loadAgents() throws IOException {
        String body = new ClassPathResource("a2a/litellm-agents.json").getContentAsString(StandardCharsets.UTF_8);
        agents = (ArrayNode) JSON_MAPPER.readTree(body);
    }

    private LiteLlmAgentDiscovery discovery(String gateway) {
        A2AClientProperties properties = new A2AClientProperties(false, 5, "http://agents", new A2AClientProperties.Litellm(gateway, API_KEY));

        WebClient webClient = new LiteLlmA2AWebClientConfig().liteLlmA2AWebClient(properties)
                .mutate()
                .exchangeFunction(request -> {
                    requests.add(request);

                    StubbedResponse stubbedResponse = responses.getOrDefault(request.url().getRawPath(),
                            new StubbedResponse(HttpStatus.NOT_FOUND, "{}"));

                    return Mono.just(ClientResponse.create(stubbedResponse.status())
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(stubbedResponse.body())
                            .build());
                })
                .build();

        return new LiteLlmAgentDiscovery(webClient, properties);
    }

    private void respondWithAgents(String path) {
        responses.put(path, new StubbedResponse(HttpStatus.OK, JSON_MAPPER.writeValueAsString(agents)));
    }

    private void respondWithAgents() {
        respondWithAgents(AGENTS_PATH);
    }

    @SuppressWarnings("all")
    private void fail(String path, HttpStatus status) {
        responses.put(path, new StubbedResponse(status, "{}"));
    }

    private ObjectNode agent(String agentName) {
        for (JsonNode agent : agents) {
            if (agentName.equals(agent.path("agent_name").asString())) {
                return (ObjectNode) agent;
            }
        }

        throw new IllegalArgumentException("No agent named " + agentName + " in the fixture");
    }

    private ObjectNode agentCardParams(String agentName) {
        return (ObjectNode) agent(agentName).get("agent_card_params");
    }

    private void interfaceUrl(String agentName, String url) {
        agentCardParams(agentName).putArray("supportedInterfaces")
                .addObject()
                .put("url", url)
                .put("protocolBinding", "JSONRPC")
                .put("protocolVersion", "1.0");
    }

    private static List<String> names(List<AgentCard> agentCards) {
        return agentCards.stream().map(AgentCard::name).toList();
    }

    private static List<String> interfaceUrls(AgentCard agentCard) {
        return agentCard.supportedInterfaces().stream().map(AgentInterface::url).toList();
    }

    private static AgentCard named(List<AgentCard> agentCards, String name) {
        return agentCards.stream()
                .filter(agentCard -> agentCard.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void discoversEveryListedAgentFromTheAgentListAlone() {
        respondWithAgents();

        List<AgentCard> agentCards = discovery(GATEWAY).discover();

        assertThat(names(agentCards)).containsExactly("logs", "network");
        assertThat(requests).extracting(request -> request.url().toString()).containsExactly(GATEWAY + AGENTS_PATH);
    }

    @Test
    void sendsTheVirtualKeyAndNoAuthorization() {
        respondWithAgents();

        discovery(GATEWAY).discover();

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.headers().getFirst("x-litellm-api-key")).isEqualTo("Bearer " + API_KEY);
            assertThat(request.headers().containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
        });
    }

    @Test
    void skipsAnAgentWithNoName() {
        agent("logs").remove("agent_name");
        respondWithAgents();

        assertThat(names(discovery(GATEWAY).discover())).containsExactly("network");
    }

    @Test
    void failsWhenTheAgentListCannotBeFetched() {
        fail(AGENTS_PATH, HttpStatus.SERVICE_UNAVAILABLE);

        LiteLlmAgentDiscovery discovery = discovery(GATEWAY);

        assertThatThrownBy(discovery::discover).isInstanceOf(WebClientResponseException.class);
    }

    /**
     * The agent name becomes the slash command, so only a plain command name is ever registered.
     */
    @Test
    void skipsAnAgentNameThatIsNotACommandName() {
        agent("logs").put("agent_name", "net/work");
        respondWithAgents();

        assertThat(names(discovery(GATEWAY).discover())).containsExactly("network");
    }

    /**
     * LiteLLM serves the agent's display name as the card's name, so the command comes from the
     * {@code agent_name} the agent is listed under.
     */
    @Test
    void namesTheCommandAfterTheAgentNameNotTheCardName() {
        respondWithAgents();

        AgentCard network = named(discovery(GATEWAY).discover(), "network");

        assertThat(network.description()).isEqualTo("Answers questions about the household network from UniFi data (read-only).");
    }

    /**
     * LiteLLM omits every capability it was not told about, and writes {@code securitySchemes} and
     * {@code security} in the A2A 0.3 shape.
     */
    @Test
    void parsesTheCardsLiteLlmActuallyServes() {
        respondWithAgents();

        List<AgentCard> agentCards = discovery(GATEWAY).discover();

        assertThat(named(agentCards, "logs")).satisfies(agentCard -> {
            assertThat(agentCard.capabilities().streaming()).isFalse();
            assertThat(interfaceUrls(agentCard)).containsExactly(GATEWAY + LOGS_ROUTE);
        });

        assertThat(named(agentCards, "network")).satisfies(agentCard -> {
            assertThat(agentCard.capabilities().streaming()).isTrue();
            assertThat(agentCard.skills()).extracting("id").containsExactly("chat");
            assertThat(interfaceUrls(agentCard)).containsExactly(GATEWAY + NETWORK_ROUTE);
        });
    }

    /**
     * Behind a TLS-terminating proxy LiteLLM writes the scheme it was reached on, so the endpoint is
     * rebuilt on the configured gateway rather than trusted.
     */
    @Test
    void rebuildsAnHttpInterfaceOnTheConfiguredHttpsGateway() {
        String gateway = "https://litellm.izzy-bot.com";
        respondWithAgents();

        assertThat(interfaceUrls(named(discovery(gateway).discover(), "network")))
                .containsExactly(gateway + NETWORK_ROUTE);
    }

    /**
     * Card pinning: a card mis-curated in LiteLLM must not be able to point the virtual key and users'
     * messages anywhere but the gateway, so only its path is kept.
     */
    @Test
    void pinsAnInterfaceOnAnotherHostBackOntoTheGateway() {
        interfaceUrl("network", "https://attacker.net:8443" + NETWORK_ROUTE);
        respondWithAgents();

        assertThat(interfaceUrls(named(discovery(GATEWAY).discover(), "network")))
                .containsExactly(GATEWAY + NETWORK_ROUTE);
    }

    @Test
    void refusesACardWithNoInterfaces() {
        agentCardParams("logs").putArray("supportedInterfaces");
        respondWithAgents();

        assertThat(names(discovery(GATEWAY).discover())).containsExactly("network");
    }

    /**
     * Pinning the host alone would let a card aim the shared key at any gateway route, such as key
     * management or chat completions.
     */
    @Test
    void refusesAnInterfaceOutsideTheGatewaysAgentRoutes() {
        interfaceUrl("logs", GATEWAY + "/key/generate");
        respondWithAgents();

        assertThat(names(discovery(GATEWAY).discover())).containsExactly("network");
    }

    @Test
    void keepsEveryCallUnderAGatewayBasePath() {
        String gateway = "http://proxy/litellm";
        interfaceUrl("logs", "http://proxy/other" + LOGS_ROUTE);
        interfaceUrl("network", "https://public.example/litellm" + NETWORK_ROUTE);
        respondWithAgents("/litellm" + AGENTS_PATH);

        assertThat(discovery(gateway).discover())
                .singleElement()
                .satisfies(agentCard -> {
                    assertThat(agentCard.name()).isEqualTo("network");
                    assertThat(interfaceUrls(agentCard)).containsExactly(gateway + NETWORK_ROUTE);
                });
    }

    @Test
    void refusesADotSegmentTraversalOutOfAGatewayBasePath() {
        String gateway = "http://proxy/litellm";
        interfaceUrl("logs", "http://proxy/litellm/a2a/../../other");
        interfaceUrl("network", gateway + NETWORK_ROUTE);
        respondWithAgents("/litellm" + AGENTS_PATH);

        assertThat(names(discovery(gateway).discover())).containsExactly("network");
    }

    @Test
    void refusesAnEncodedTraversalUnderAGatewayBasePath() {
        String gateway = "http://proxy/litellm";
        interfaceUrl("logs", "http://proxy/litellm/a2a/%2e%2e/other");
        interfaceUrl("network", gateway + NETWORK_ROUTE);
        respondWithAgents("/litellm" + AGENTS_PATH);

        assertThat(names(discovery(gateway).discover())).containsExactly("network");
    }

    @Test
    void refusesAnUnparseableInterfaceUrl() {
        interfaceUrl("logs", "http://bad host" + LOGS_ROUTE);
        respondWithAgents();

        assertThat(names(discovery(GATEWAY).discover())).containsExactly("network");
    }
}
