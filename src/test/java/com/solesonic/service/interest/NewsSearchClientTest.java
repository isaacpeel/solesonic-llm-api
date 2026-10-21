package com.solesonic.service.interest;

import com.solesonic.config.JacksonConfig;
import com.solesonic.exception.interest.NewsSearchException;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewsSearchClientTest {

    @Mock
    private McpSyncClient mcpSyncClient;

    private NewsSearchClient newsSearchClient;

    @BeforeEach
    void setUp() {
        newsSearchClient = new NewsSearchClient(mcpSyncClient, new JacksonConfig().jsonMapper());
    }

    /**
     * Without a progress token the server emits no progress, so nothing tries to route a notification
     * that has no chat to belong to.
     */
    @Test
    void searchesNewsWithNoProgressTokenAndReadsTheServersShape() {
        when(mcpSyncClient.callTool(any())).thenReturn(McpSchema.CallToolResult.builder().addTextContent("""
                {"answer":"x","results":[{"title":"Race","url":"https://site.example/a","content":"Snippet",
                 "relevanceScore":0.8,"rawContent":"ignored"}],"responseTime":"1.2"}
                """).build());

        List<NewsSearchClient.SearchResult> results = newsSearchClient.search("Formula 1", 10, "day");

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.title()).isEqualTo("Race");
            assertThat(result.url()).isEqualTo("https://site.example/a");
            assertThat(result.relevanceScore()).isEqualTo(0.8);
        });

        ArgumentCaptor<McpSchema.CallToolRequest> request = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(mcpSyncClient).callTool(request.capture());
        assertThat(request.getValue().name()).isEqualTo("web_search_news");
        assertThat(request.getValue().arguments()).containsEntry("query", "Formula 1").containsEntry("maxResults", 10)
                .containsEntry("timeRange", "day");

        Map<String, Object> meta = request.getValue().meta();
        assertThat(meta == null || !meta.containsKey("progressToken")).isTrue();
    }

    /**
     * A realm whose service account lacks the search role shows up here, as an error result.
     */
    @Test
    void anErrorResultFailsTheRun() {
        when(mcpSyncClient.callTool(any())).thenReturn(McpSchema.CallToolResult.builder().addTextContent("Access Denied").isError(true).build());

        assertThatThrownBy(() -> newsSearchClient.search("q", 10, "day")).isInstanceOf(NewsSearchException.class);
    }

    @Test
    void aTransportFailureFailsTheRun() {
        when(mcpSyncClient.callTool(any())).thenThrow(new IllegalStateException("connection refused"));

        assertThatThrownBy(() -> newsSearchClient.search("q", 10, "day")).isInstanceOf(NewsSearchException.class);
    }

    @Test
    void extractsArticleTextByUrl() {
        when(mcpSyncClient.callTool(any())).thenReturn(McpSchema.CallToolResult.builder().addTextContent("""
                {"extractedContent":[{"url":"https://site.example/a","content":"Full text"}],"errors":[]}
                """).build());

        assertThat(newsSearchClient.extract(List.of("https://site.example/a"))).containsEntry("https://site.example/a", "Full text");
    }

    @Test
    void anExtractionFailureIsAnEmptyMapNotAFailure() {
        when(mcpSyncClient.callTool(any())).thenThrow(new IllegalStateException("timeout"));

        assertThat(newsSearchClient.extract(List.of("https://site.example/a"))).isEmpty();
    }
}
