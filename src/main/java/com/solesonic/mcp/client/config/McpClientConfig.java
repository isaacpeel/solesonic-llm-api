
package com.solesonic.mcp.client.config;

import com.solesonic.mcp.client.SecurityContextPropagatingMcpToolCallbackProvider;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration for MCP clients with security context propagation.
 * This ensures that user authentication information is available during MCP tool execution.
 */
@Configuration
public class McpClientConfig {
    private static final Logger log = LoggerFactory.getLogger(McpClientConfig.class);

    /**
     * Creates a SecurityContextPropagatingMcpToolCallbackProvider for each McpSyncClient.
     * Spring AI auto-configuration will provide the McpSyncClient beans.
     */
    @Bean
    public SecurityContextPropagatingMcpToolCallbackProvider securityContextPropagatingMcpToolCallbackProvider(
            List<McpSyncClient> mcpSyncClients) {

        if (mcpSyncClients.isEmpty()) {
            log.warn("No MCP clients configured. MCP tools will not be available.");
            throw new IllegalStateException("At least one MCP client must be configured");
        }

        // Use the first MCP client (or you can create multiple providers if needed)
        McpSyncClient mcpClient = mcpSyncClients.getFirst();
        log.info("Creating SecurityContextPropagatingMcpToolCallbackProvider with MCP client: {}",
                mcpClient.getClientInfo().name());

        return new SecurityContextPropagatingMcpToolCallbackProvider(mcpClient);
    }
}