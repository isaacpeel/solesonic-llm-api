package com.solesonic.mcp.client.config;

import com.solesonic.mcp.client.McpIdentityProvider;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.client.common.autoconfigure.configurer.McpAsyncClientConfigurer;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
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
    private McpAsyncClient mcpClient;

    /**
     * Creates a SecurityContextPropagatingMcpToolCallbackProvider for each McpAsyncClient.
     * Spring AI auto-configuration will provide the McpAsyncClient beans.
     */
    @Bean
    public McpIdentityProvider securityContextPropagatingMcpToolCallbackProvider(List<McpAsyncClient> mcpAsyncClients) {

        if (mcpAsyncClients.isEmpty()) {
            log.warn("No MCP clients configured. MCP tools will not be available.");
            throw new IllegalStateException("At least one MCP client must be configured");
        }

        McpAsyncClient mcpAsyncClient = mcpAsyncClients.getFirst();
        McpSchema.Implementation clientInfo = mcpAsyncClient.getClientInfo();
        String clientName = clientInfo.name();

        mcpAsyncClients.forEach(asyncClient -> log.info("Available client: '{}'", asyncClient.getClientInfo().name()));

        McpSchema.ClientCapabilities clientCapabilities = mcpAsyncClient.getClientCapabilities();

        log.info("Creating SecurityContextPropagatingMcpToolCallbackProvider with MCP client: {}", clientName);

        if (clientCapabilities != null) {
            log.info("MCP ClientCapabilities on startup: {}", clientCapabilities);
        } else {
            log.warn("MCP ClientCapabilities are null on startup; expected elicitation to be enabled");
        }

        this.mcpClient = mcpAsyncClient;

        return new McpIdentityProvider(mcpAsyncClient);
    }

    @Bean
    public McpAsyncClient mcpAsyncClient() {
        return this.mcpClient;
    }

    @Bean
    public McpClientCustomizer<McpClient.AsyncSpec> elicitationCapabilityCustomizer() {
        return (serverConfigurationName, asyncSpec) -> {
            log.info("Customizing MCP client '{}' to enable elicitation capability", serverConfigurationName);
            asyncSpec.capabilities(McpSchema.ClientCapabilities.builder()
                    .elicitation(true, false)
                    .sampling()
                    .build());
        };
    }

}