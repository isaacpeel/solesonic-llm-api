package com.solesonic.mcp.client.config;

import com.solesonic.mcp.client.McpIdentityProvider;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for MCP clients with security context propagation.
 * This ensures that user authentication information is available during MCP tool execution.
 */
@Configuration
public class McpClientConfig {
    private static final Logger log = LoggerFactory.getLogger(McpClientConfig.class);
    private McpSyncClient mcpClient;

    /**
     * Creates a SecurityContextPropagatingMcpToolCallbackProvider for each McpAsyncClient.
     * Spring AI auto-configuration will provide the McpAsyncClient beans.
     */
    @Bean
    public McpIdentityProvider securityContextPropagatingMcpToolCallbackProvider(List<McpSyncClient> mcpSyncClients) {

        if (mcpSyncClients.isEmpty()) {
            log.warn("No MCP clients configured. MCP tools will not be available.");
            throw new IllegalStateException("At least one MCP client must be configured");
        }

        McpSyncClient mcpSyncClient = mcpSyncClients.getFirst();
        McpSchema.Implementation clientInfo = mcpSyncClient.getClientInfo();
        String clientName = clientInfo.name();

        mcpSyncClients.forEach(asyncClient -> log.info("Available client: '{}'", asyncClient.getClientInfo().name()));

        McpSchema.ClientCapabilities clientCapabilities = mcpSyncClient.getClientCapabilities();

        log.info("Creating SecurityContextPropagatingMcpToolCallbackProvider with MCP client: {}", clientName);

        if (clientCapabilities != null) {
            log.info("MCP ClientCapabilities on startup: {}", clientCapabilities);
        } else {
            log.warn("MCP ClientCapabilities are null on startup; expected elicitation to be enabled");
        }

        this.mcpClient = mcpSyncClient;

        return new McpIdentityProvider(mcpSyncClient);
    }

    @Bean
    public McpSyncClient mcpSyncClient() {
        return this.mcpClient;
    }

    @Bean
    public McpClientCustomizer<McpClient.SyncSpec> elicitationCapabilityCustomizer(@Value("${solesonic.elicitation.timeout-seconds:600}") long timeoutSeconds) {
        return (serverConfigurationName, syncSpec) -> {
            log.info("Customizing MCP client '{}' to enable elicitation capability and set request timeout to {}s", serverConfigurationName, timeoutSeconds);

            syncSpec.capabilities(McpSchema.ClientCapabilities.builder()
                    .elicitation(true, false)
                    .sampling()
                    .build())
                    .requestTimeout(Duration.ofSeconds(timeoutSeconds));
        };
    }

}