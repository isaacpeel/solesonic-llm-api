
package com.solesonic.mcp.client.config;

import com.solesonic.mcp.client.McpIdentityProvider;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
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
    public McpIdentityProvider securityContextPropagatingMcpToolCallbackProvider(List<McpSyncClient> mcpSyncClients) {

        if (mcpSyncClients.isEmpty()) {
            log.warn("No MCP clients configured. MCP tools will not be available.");
            throw new IllegalStateException("At least one MCP client must be configured");
        }


        McpSyncClient mcpClient = mcpSyncClients.getFirst();
        McpSchema.Implementation clientInfo = mcpClient.getClientInfo();
        String clientName = clientInfo.name();

        McpSchema.ClientCapabilities clientCapabilities = mcpClient.getClientCapabilities();

        log.info("Creating SecurityContextPropagatingMcpToolCallbackProvider with MCP client: {}", clientName);

        return new McpIdentityProvider(mcpClient);
    }

//    @Bean
//    public McpSchema.ClientCapabilities mcpClientCapabilities() {
//        McpSchema.ClientCapabilities.RootCapabilities rootCapabilities = new McpSchema.ClientCapabilities.RootCapabilities(true);
//        McpSchema.ClientCapabilities.Sampling sampling = new McpSchema.ClientCapabilities.Sampling();
//        McpSchema.ClientCapabilities.Elicitation elicitation = new McpSchema.ClientCapabilities.Elicitation();
//
//        return new McpSchema.ClientCapabilities(Map.of(), rootCapabilities, sampling, elicitation);
//    }
}