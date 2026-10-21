package com.solesonic.service.interest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.solesonic.exception.interest.NewsSearchException;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Calls the MCP server's news search and extraction tools for a background run. There is no user on
 * the thread, so {@code WebClientConfig} sends the application's client credentials; the user's own
 * right to search was checked once, when they accepted the offer. No progress token is sent, so the
 * server emits no progress for a request that has no chat to report it to.
 * <p>
 * The server's response shapes are copied here, not imported, and anything not needed is ignored —
 * {@code rawContent} included, which is never read.
 */
@Component
public class NewsSearchClient {
    private static final Logger log = LoggerFactory.getLogger(NewsSearchClient.class);

    static final String WEB_SEARCH_NEWS = "web_search_news";
    static final String WEB_EXTRACT_CONTENT = "web_extract_content";

    private final McpSyncClient mcpSyncClient;
    private final JsonMapper jsonMapper;

    public NewsSearchClient(McpSyncClient mcpSyncClient, JsonMapper jsonMapper) {
        this.mcpSyncClient = mcpSyncClient;
        this.jsonMapper = jsonMapper;
    }

    /**
     * @throws NewsSearchException when the search did not answer; the run fails
     */
    public List<SearchResult> search(String query, int maxResults, String timeRange) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", query);
        arguments.put("maxResults", maxResults);
        arguments.put("timeRange", timeRange);

        String text = call(WEB_SEARCH_NEWS, arguments);

        try {
            SearchResponse searchResponse = jsonMapper.readValue(text, SearchResponse.class);

            return searchResponse.results() == null ? List.of() : searchResponse.results();
        } catch (JacksonException jacksonException) {
            throw new NewsSearchException("The news search answered with a body that would not parse", jacksonException);
        }
    }

    /**
     * Full article text by URL, as the server returned each URL. Never fails the run: an extraction
     * that does not answer is an empty map, and the run falls back to the search snippets.
     */
    public Map<String, String> extract(List<String> urls) {
        if (urls.isEmpty()) {
            return Map.of();
        }

        try {
            String text = call(WEB_EXTRACT_CONTENT, Map.of("urls", urls));
            ExtractResponse extractResponse = jsonMapper.readValue(text, ExtractResponse.class);

            if (extractResponse.extractedContent() == null) {
                return Map.of();
            }

            return extractResponse.extractedContent().stream()
                    .filter(result -> result.url() != null && result.content() != null)
                    .collect(Collectors.toMap(ExtractResult::url, ExtractResult::content, (first, _) -> first, HashMap::new));
        } catch (RuntimeException exception) {
            log.warn("News extraction failed, falling back to snippets ({})", exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    private String call(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolResult callToolResult;

        try {
            callToolResult = mcpSyncClient.callTool(McpSchema.CallToolRequest.builder(toolName).arguments(arguments).build());
        } catch (RuntimeException exception) {
            throw new NewsSearchException("The " + toolName + " call failed", exception);
        }

        List<McpSchema.Content> content = callToolResult.content() == null ? List.of() : callToolResult.content();

        String text = content.stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .collect(Collectors.joining());

        if (Boolean.TRUE.equals(callToolResult.isError())) {
            throw new NewsSearchException("The " + toolName + " call was refused");
        }

        if (text.isBlank()) {
            throw new NewsSearchException("The " + toolName + " call answered with nothing");
        }

        return text;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResult(String title, String url, String content, Double relevanceScore) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<SearchResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExtractResult(String url, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExtractResponse(List<ExtractResult> extractedContent) {
    }
}
