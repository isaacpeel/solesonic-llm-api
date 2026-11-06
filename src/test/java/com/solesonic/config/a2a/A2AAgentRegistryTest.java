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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class A2AAgentRegistryTest {

    private static final String GATEWAY = "http://litellm:4000";

    @Mock
    private LiteLlmAgentDiscovery liteLlmAgentDiscovery;

    private A2AAgentRegistry registry() {
        return new A2AAgentRegistry(liteLlmAgentDiscovery);
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
    void registersLiteLlmAgents() {
        when(liteLlmAgentDiscovery.discover()).thenReturn(List.of(liteLlmCard("jira"), liteLlmCard("network")));

        A2AAgentRegistry registry = registry();
        registry.discoverAgents();

        assertThat(registry.agent("network")).map(RegisteredAgent::source).contains(A2AAgentSource.LITELLM);
        assertThat(registry.asSlashCommands()).extracting(SlashCommand::command).containsExactly("jira", "network");
    }

    @Test
    void startsWithNoAgentsWhenLiteLlmIsDown() {
        when(liteLlmAgentDiscovery.discover()).thenThrow(new IllegalStateException("connection refused"));

        A2AAgentRegistry registry = registry();
        registry.discoverAgents();

        assertThat(registry.asSlashCommands()).isEmpty();
    }

    @Test
    void aRefreshOffersAnAgentAddedToLiteLlmAndWithdrawsOneRemoved() {
        when(liteLlmAgentDiscovery.discover())
                .thenReturn(List.of(liteLlmCard("jira"), liteLlmCard("network")))
                .thenReturn(List.of(liteLlmCard("network"), liteLlmCard("nba")));

        A2AAgentRegistry registry = registry();
        registry.discoverAgents();
        registry.refreshLiteLlmAgents();

        assertThat(registry.agent("jira")).isEmpty();
        assertThat(registry.agent("nba")).map(RegisteredAgent::source).contains(A2AAgentSource.LITELLM);
        assertThat(registry.asSlashCommands()).extracting(SlashCommand::command).containsExactly("network", "nba");
    }

    @Test
    void aRefreshFindsAgentsWhenLiteLlmWasDownAtStartup() {
        when(liteLlmAgentDiscovery.discover())
                .thenThrow(new IllegalStateException("connection refused"))
                .thenReturn(List.of(liteLlmCard("network")));

        A2AAgentRegistry registry = registry();
        registry.discoverAgents();
        registry.refreshLiteLlmAgents();

        assertThat(registry.asSlashCommands()).extracting(SlashCommand::command).containsExactly("network");
    }

    @Test
    void aFailedRefreshKeepsTheKnownAgents() {
        when(liteLlmAgentDiscovery.discover())
                .thenReturn(List.of(liteLlmCard("network")))
                .thenThrow(new IllegalStateException("connection refused"));

        A2AAgentRegistry registry = registry();
        registry.discoverAgents();
        registry.refreshLiteLlmAgents();

        assertThat(registry.agent("network")).isPresent();
    }

    @Test
    void aRefreshWithNoAgentsListedWithdrawsThemAll() {
        when(liteLlmAgentDiscovery.discover())
                .thenReturn(List.of(liteLlmCard("network")))
                .thenReturn(List.of());

        A2AAgentRegistry registry = registry();
        registry.discoverAgents();
        registry.refreshLiteLlmAgents();

        assertThat(registry.asSlashCommands()).isEmpty();
    }

    @Test
    void anUnknownAgentIsEmpty() {
        when(liteLlmAgentDiscovery.discover()).thenReturn(List.of());

        A2AAgentRegistry registry = registry();
        registry.discoverAgents();

        assertThat(registry.agent("network")).isEmpty();
    }
}
