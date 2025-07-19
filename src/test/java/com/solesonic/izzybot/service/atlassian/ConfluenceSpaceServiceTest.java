package com.solesonic.izzybot.service.atlassian;

import com.solesonic.izzybot.model.atlassian.confluence.Space;
import com.solesonic.izzybot.model.atlassian.confluence.SpacesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ConfluenceSpaceServiceTest {

    @Mock
    private WebClient webClient;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    private ConfluenceSpaceService confluenceSpaceService;

    @BeforeEach
    public void setUp() {
        confluenceSpaceService = new ConfluenceSpaceService(webClient);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testGetSpaces() {
        // Prepare test data
        SpacesResponse spacesResponse = new SpacesResponse();
        Space space = new Space();
        space.setId("space-id-1");
        space.setName("Test Space");

        spacesResponse.setResults(List.of(space));

        // Mock WebClient for getSpaces() method
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.exchangeToMono(any())).thenReturn(Mono.just(spacesResponse));

        // Test getSpaces() method
        SpacesResponse spaces = confluenceSpaceService.getSpaces();

        // Verify results
        assertThat(spaces).isNotNull();
        assertThat(spaces.getResults()).hasSize(1);
        assertThat(spaces.getResults().getFirst().getName()).isEqualTo("Test Space");

        // Mock WebClient for getSpace() method
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.exchangeToMono(any())).thenReturn(Mono.just(space));

        // Test getSpace() method
        Space singleSpace = confluenceSpaceService.getSpace("space-id-1");
        assertThat(singleSpace).isNotNull();
        assertThat(singleSpace.getName()).isEqualTo("Test Space");
    }
}