package com.solesonic.model.a2a;

import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LiteLlmAgentTest {

    @Test
    void decodesTheAgentListLiteLlmServes() throws IOException {
        String body = new ClassPathResource("a2a/litellm-agents.json").getContentAsString(StandardCharsets.UTF_8);

        WebClient webClient = WebClient.builder()
                .baseUrl("http://litellm:4000")
                .exchangeFunction(_ -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()))
                .build();

        List<LiteLlmAgent> agents = webClient.get()
                .uri("/v1/agents")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<LiteLlmAgent>>() {})
                .block();

        assertThat(agents).extracting(LiteLlmAgent::agentName).containsExactly("logs", "network");
        assert agents != null;
        assertThat(agents.getFirst().agentCardParams().capabilities().toAgentCapabilities().streaming()).isFalse();
        assertThat(agents.getLast().agentCardParams().capabilities().toAgentCapabilities().streaming()).isTrue();
        assertThat(agents.getLast().agentCardParams().supportedInterfaces().getFirst().url()).isEqualTo("http://litellm.izzy-bot.com/a2a/22932c7f-2ccb-4bf4-a54e-bbc27823485b");
    }
}
