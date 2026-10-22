package com.solesonic.config.a2a;

import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiteLlmAgentDiscoveryTest {

    private static final String GATEWAY = "http://litellm:4000";
    private static final String API_KEY = "sk-izzybot";

    private final List<ClientRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, StubbedResponse> responses = new HashMap<>();

    private record StubbedResponse(HttpStatus status, String body) {
    }

    private LiteLlmAgentDiscovery discovery(String gateway) {
        A2AClientProperties properties = new A2AClientProperties(false, 5, "http://agents",
                new A2AClientProperties.Litellm(gateway, API_KEY));

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

    private void respond(String path, String body) {
        responses.put(path, new StubbedResponse(HttpStatus.OK, body));
    }

    private void fail(String path, HttpStatus status) {
        responses.put(path, new StubbedResponse(status, "{}"));
    }

    private static String agents(String... agentNames) {
        StringBuilder body = new StringBuilder("[");

        for (int index = 0; index < agentNames.length; index++) {
            if (index > 0) {
                body.append(',');
            }

            body.append("""
                    {"agent_id":"id-%d","agent_name":"%s","litellm_params":{"model":"a2a/%s"}}"""
                    .formatted(index, agentNames[index], agentNames[index]));
        }

        return body.append(']').toString();
    }

    private static String card(String name, String interfaceUrl) {
        return card(name, interfaceUrl, null);
    }

    private static String card(String name, String interfaceUrl, String legacyUrl) {
        String interfaces = interfaceUrl == null
                ? "[]"
                : """
                [{"protocolBinding":"JSONRPC","url":"%s","protocolVersion":"1.0"}]""".formatted(interfaceUrl);

        String legacy = legacyUrl == null ? "" : ",\"url\":\"%s\"".formatted(legacyUrl);

        return """
                {"name":"%s","description":"Answers questions about the network","version":"1.0.0",
                 "capabilities":{"streaming":true,"pushNotifications":false,"extendedAgentCard":false},
                 "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],
                 "skills":[{"id":"ask","name":"ask","description":"Ask a question","tags":[]}],
                 "supportedInterfaces":%s%s}""".formatted(name, interfaces, legacy);
    }

    private static List<String> names(List<AgentCard> agentCards) {
        return agentCards.stream().map(AgentCard::name).toList();
    }

    private static List<String> interfaceUrls(AgentCard agentCard) {
        return agentCard.supportedInterfaces().stream().map(AgentInterface::url).toList();
    }

    @Test
    void discoversEveryListedAgentThroughTheGateway() {
        respond("/v1/agents", agents("network", "weather"));
        respond("/a2a/network/.well-known/agent-card.json", card("network", GATEWAY + "/a2a/network"));
        respond("/a2a/weather/.well-known/agent-card.json", card("weather", GATEWAY + "/a2a/weather"));

        List<AgentCard> agentCards = discovery(GATEWAY).discover();

        assertThat(names(agentCards)).containsExactly("network", "weather");
        assertThat(requests).extracting(request -> request.url().toString()).containsExactly(
                GATEWAY + "/v1/agents",
                GATEWAY + "/a2a/network/.well-known/agent-card.json",
                GATEWAY + "/a2a/weather/.well-known/agent-card.json");
    }

    @Test
    void sendsTheVirtualKeyAndNoAuthorizationOnEveryCall() {
        respond("/v1/agents", agents("network"));
        respond("/a2a/network/.well-known/agent-card.json", card("network", GATEWAY + "/a2a/network"));

        discovery(GATEWAY).discover();

        assertThat(requests).hasSize(2).allSatisfy(request -> {
            assertThat(request.headers().getFirst("x-litellm-api-key")).isEqualTo("Bearer " + API_KEY);
            assertThat(request.headers().containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
        });
    }

    @Test
    void skipsAnAgentWhoseCardCannotBeFetchedAndKeepsTheRest() {
        respond("/v1/agents", agents("weather", "network"));
        fail("/a2a/weather/.well-known/agent-card.json", HttpStatus.INTERNAL_SERVER_ERROR);
        respond("/a2a/network/.well-known/agent-card.json", card("network", GATEWAY + "/a2a/network"));

        assertThat(names(discovery(GATEWAY).discover())).containsExactly("network");
    }

    @Test
    void skipsAnAgentWithNoName() {
        respond("/v1/agents", """
                [{"agent_id":"id-0"},{"agent_id":"id-1","agent_name":"network"}]""");
        respond("/a2a/network/.well-known/agent-card.json", card("network", GATEWAY + "/a2a/network"));

        assertThat(names(discovery(GATEWAY).discover())).containsExactly("network");
    }

    @Test
    void failsWhenTheAgentListCannotBeFetched() {
        fail("/v1/agents", HttpStatus.SERVICE_UNAVAILABLE);

        LiteLlmAgentDiscovery discovery = discovery(GATEWAY);

        assertThatThrownBy(discovery::discover).isInstanceOf(WebClientResponseException.class);
    }

    /**
     * The agent name becomes a path segment and the slash command, so only a plain command name is
     * ever fetched.
     */
    @Test
    void neverFetchesACardForAnAgentNameThatIsNotACommandName() {
        respond("/v1/agents", agents("..", "net/work", "network"));
        respond("/a2a/network/.well-known/agent-card.json", card("network", GATEWAY + "/a2a/network"));

        assertThat(names(discovery(GATEWAY).discover())).containsExactly("network");
        assertThat(requests).extracting(request -> request.url().getRawPath())
                .containsExactly("/v1/agents", "/a2a/network/.well-known/agent-card.json");
    }

    /**
     * LiteLLM serves the agent's display name as the card's name, so the command comes from the
     * {@code agent_name} the card was fetched by.
     */
    @Test
    void namesTheCommandAfterTheAgentNameNotTheCardName() {
        respond("/v1/agents", agents("network"));
        respond("/a2a/network/.well-known/agent-card.json", card("Network Analyst", GATEWAY + "/a2a/network"));

        List<AgentCard> agentCards = discovery(GATEWAY).discover();

        assertThat(names(agentCards)).containsExactly("network");
        assertThat(agentCards.getFirst().description()).isEqualTo("Answers questions about the network");
    }

    /**
     * The card LiteLLM serves, verbatim: a display name, an http URL, and {@code securitySchemes} and
     * {@code security} in the A2A 0.3 shape, which a plain Jackson decode of {@link AgentCard} cannot read.
     */
    @Test
    void parsesTheCardLiteLlmActuallyServes() {
        respond("/v1/agents", agents("network"));
        respond("/a2a/network/.well-known/agent-card.json", """
                {"url": "http://litellm.izzy-bot.com/a2a/network", "name": "Network Analyst",
                 "skills": [{"id": "chat", "name": "Chat", "tags": ["chat"], "description": "Conversational interaction with the agent."}],
                 "version": "1.0.0", "provider": {"url": "http://litellm.izzy-bot.com", "organization": "LiteLLM Proxy"},
                 "security": [{"LiteLLMKey": []}],
                 "description": "Answers questions about the household network from UniFi data (read-only).",
                 "capabilities": {"streaming": true}, "protocolVersion": "1.0",
                 "securitySchemes": {"LiteLLMKey": {"type": "http", "scheme": "bearer", "description": "LiteLLM virtual key"}},
                 "defaultInputModes": ["text"], "defaultOutputModes": ["text"],
                 "supportedInterfaces": [{"url": "http://litellm.izzy-bot.com/a2a/network", "protocolBinding": "JSONRPC", "protocolVersion": "1.0"}]}""");

        assertThat(discovery(GATEWAY).discover())
                .singleElement()
                .satisfies(agentCard -> {
                    assertThat(agentCard.name()).isEqualTo("network");
                    assertThat(agentCard.description()).isEqualTo("Answers questions about the household network from UniFi data (read-only).");
                    assertThat(agentCard.capabilities().streaming()).isTrue();
                    assertThat(interfaceUrls(agentCard)).containsExactly(GATEWAY + "/a2a/network");
                });
    }

    /**
     * Behind a TLS-terminating proxy LiteLLM writes the scheme it was reached on, so the endpoint is
     * rebuilt on the configured gateway rather than trusted.
     */
    @Test
    void rebuildsAnHttpInterfaceOnTheConfiguredHttpsGateway() {
        String gateway = "https://litellm.example.com";
        respond("/v1/agents", agents("network"));
        respond("/a2a/network/.well-known/agent-card.json", card("network", "http://litellm.example.com/a2a/network"));

        assertThat(discovery(gateway).discover())
                .singleElement()
                .satisfies(agentCard -> assertThat(interfaceUrls(agentCard))
                        .containsExactly("https://litellm.example.com/a2a/network"));
    }

    /**
     * Card pinning: a card mis-curated in LiteLLM must not be able to point the virtual key and users'
     * messages anywhere but the gateway, so only its path is kept.
     */
    @Test
    void pinsAnInterfaceOnAnotherHostBackOntoTheGateway() {
        respond("/v1/agents", agents("network"));
        respond("/a2a/network/.well-known/agent-card.json", card("network", "https://attacker.net:8443/a2a/network"));

        assertThat(discovery(GATEWAY).discover())
                .singleElement()
                .satisfies(agentCard -> assertThat(interfaceUrls(agentCard)).containsExactly(GATEWAY + "/a2a/network"));
    }

    @Test
    void dropsTheLegacyEndpointsTheClientNeverUses() {
        respond("/v1/agents", agents("network"));
        respond("/a2a/network/.well-known/agent-card.json",
                card("network", GATEWAY + "/a2a/network", "http://elsewhere/a2a/network"));

        assertThat(discovery(GATEWAY).discover())
                .singleElement()
                .satisfies(agentCard -> {
                    assertThat(agentCard.url()).isNull();
                    assertThat(agentCard.additionalInterfaces()).isNull();
                });
    }

    @Test
    void refusesACardWithNoInterfaces() {
        respond("/v1/agents", agents("network"));
        respond("/a2a/network/.well-known/agent-card.json", card("network", null));

        assertThat(discovery(GATEWAY).discover()).isEmpty();
    }

    /**
     * Pinning the host alone would let a card aim the shared key at any gateway route, such as key
     * management or chat completions.
     */
    @Test
    void refusesAnInterfaceOutsideTheGatewaysAgentRoutes() {
        respond("/v1/agents", agents("network"));
        respond("/a2a/network/.well-known/agent-card.json", card("network", GATEWAY + "/key/generate"));

        assertThat(discovery(GATEWAY).discover()).isEmpty();
    }

    @Test
    void keepsEveryCallUnderAGatewayBasePath() {
        String gateway = "http://proxy/litellm";
        respond("/litellm/v1/agents", agents("network", "outside", "escaping"));
        respond("/litellm/a2a/network/.well-known/agent-card.json", card("network", "https://public.example/litellm/a2a/network"));
        respond("/litellm/a2a/outside/.well-known/agent-card.json", card("outside", "http://proxy/other/a2a/outside"));
        respond("/litellm/a2a/escaping/.well-known/agent-card.json", card("escaping", "http://proxy/litellm/a2a/../../other"));

        assertThat(discovery(gateway).discover())
                .singleElement()
                .satisfies(agentCard -> {
                    assertThat(agentCard.name()).isEqualTo("network");
                    assertThat(interfaceUrls(agentCard)).containsExactly("http://proxy/litellm/a2a/network");
                });
    }

    @Test
    void refusesAnEncodedTraversalUnderAGatewayBasePath() {
        String gateway = "http://proxy/litellm";
        respond("/litellm/v1/agents", agents("network"));
        respond("/litellm/a2a/network/.well-known/agent-card.json",
                card("network", "http://proxy/litellm/a2a/%2e%2e/other"));

        assertThat(discovery(gateway).discover()).isEmpty();
    }

    @Test
    void refusesAnUnparseableInterfaceUrl() {
        respond("/v1/agents", agents("network"));
        respond("/a2a/network/.well-known/agent-card.json", card("network", "http://bad host/a2a/network"));

        assertThat(discovery(GATEWAY).discover()).isEmpty();
    }
}
