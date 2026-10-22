package com.solesonic.config.a2a;

import com.solesonic.model.a2a.A2AAgentSource;
import com.solesonic.model.a2a.RegisteredAgent;
import com.solesonic.model.prompt.SlashCommand;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class A2AAgentRegistryTest {

    private static final String AGENT_HOST = "http://agents";
    private static final String GATEWAY = "http://litellm:4000";
    private static final String AGENT_LIST_PATH = "/a2a/agents";

    @Mock
    private LiteLlmAgentDiscovery liteLlmAgentDiscovery;

    private A2AAgentRegistry registry(HttpStatus agentListStatus) {
        A2AClientProperties properties = new A2AClientProperties(false, 5, AGENT_HOST,
                new A2AClientProperties.Litellm(GATEWAY, "sk-izzybot"));

        WebClient agentHostWebClient = WebClient.builder()
                .baseUrl(AGENT_HOST)
                .exchangeFunction(request -> {
                    boolean agentList = request.url().getPath().equals(AGENT_LIST_PATH);

                    return Mono.just(ClientResponse.create(agentList ? agentListStatus : HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(agentList ? "[\"" + AGENT_HOST + "/jira/card\"]" : hostCardJson())
                            .build());
                })
                .build();

        return new A2AAgentRegistry(agentHostWebClient, liteLlmAgentDiscovery, properties);
    }

    private static String hostCardJson() {
        return """
                {"name":"jira","description":"Works Jira tickets","version":"1.0.0",
                 "capabilities":{"streaming":true,"pushNotifications":false,"extendedAgentCard":false},
                 "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],
                 "skills":[],
                 "supportedInterfaces":[{"protocolBinding":"JSONRPC","url":"http://agents/jira","protocolVersion":"1.0"}]}""";
    }

    @SuppressWarnings("all")
    private static AgentCard liteLlmCard(String name) {
        return AgentCard.builder()
                .name(name)
                .description("From LiteLLM")
                .version("1.0.0")
                .capabilities(new AgentCapabilities(true, false, false, null))
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(List.of())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", GATEWAY + "/a2a/" + name, null)))
                .build();
    }

    @Test
    void registersAgentsFromBothSources() {
        when(liteLlmAgentDiscovery.discover()).thenReturn(List.of(liteLlmCard("network")));

        A2AAgentRegistry registry = registry(HttpStatus.OK);
        registry.discoverAgents();

        assertThat(registry.agent("jira")).map(RegisteredAgent::source).contains(A2AAgentSource.AGENT_HOST);
        assertThat(registry.agent("network")).map(RegisteredAgent::source).contains(A2AAgentSource.LITELLM);
        assertThat(registry.asSlashCommands()).extracting(SlashCommand::command).containsExactly("jira", "network");
    }

    /**
     * Only {@code /network} is lost when LiteLLM is down; the agent host's agents and startup are not.
     */
    @Test
    void startsWithTheAgentHostsAgentsWhenLiteLlmIsDown() {
        when(liteLlmAgentDiscovery.discover()).thenThrow(new IllegalStateException("connection refused"));

        A2AAgentRegistry registry = registry(HttpStatus.OK);
        registry.discoverAgents();

        assertThat(registry.asSlashCommands()).extracting(SlashCommand::command).containsExactly("jira");
        assertThat(registry.agent("network")).isEmpty();
    }

    @Test
    void keepsTheAgentHostsAgentOnANameConflict() {
        when(liteLlmAgentDiscovery.discover()).thenReturn(List.of(liteLlmCard("jira")));

        A2AAgentRegistry registry = registry(HttpStatus.OK);
        registry.discoverAgents();

        assertThat(registry.agent("jira")).hasValueSatisfying(registeredAgent -> {
            assertThat(registeredAgent.source()).isEqualTo(A2AAgentSource.AGENT_HOST);
            assertThat(registeredAgent.agentCard().description()).isEqualTo("Works Jira tickets");
        });
        assertThat(registry.asSlashCommands()).hasSize(1);
    }

    @Test
    void stillFailsStartupWhenTheAgentHostIsDown() {
        A2AAgentRegistry registry = registry(HttpStatus.SERVICE_UNAVAILABLE);

        assertThatThrownBy(registry::discoverAgents).isInstanceOf(RuntimeException.class);
    }

    @Test
    void anUnknownAgentIsEmpty() {
        when(liteLlmAgentDiscovery.discover()).thenReturn(List.of());

        A2AAgentRegistry registry = registry(HttpStatus.OK);
        registry.discoverAgents();

        assertThat(registry.agent("network")).isEmpty();
    }
}
