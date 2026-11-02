package com.solesonic.tools;

import com.solesonic.model.prompt.LocalToolSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-process tools, each wrapped in a {@link LocalToolCallback} so it runs as the user whose turn
 * it is and sees the whole tool context.
 */
@Component
public class LocalToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(LocalToolRegistry.class);

    private final Map<String, ToolCallback> toolCallbacksByName = new LinkedHashMap<>();

    public LocalToolRegistry(List<LocalTool> localTools,
                             JwtDecoder jwtDecoder,
                             JwtAuthenticationConverter jwtAuthenticationConverter) {
        for (LocalTool localTool : localTools) {
            for (ToolCallback toolCallback : ToolCallbacks.from(localTool)) {
                String toolName = toolCallback.getToolDefinition().name();
                toolCallbacksByName.put(toolName, new LocalToolCallback(toolCallback, jwtDecoder, jwtAuthenticationConverter));

                log.debug("Registered local tool: {}", toolName);
            }
        }

        log.info("Local tool registry initialized with {} tool(s)", toolCallbacksByName.size());
    }

    public ToolCallback callback(String toolName) {
        return toolCallbacksByName.get(toolName);
    }

    public boolean contains(String toolName) {
        return toolCallbacksByName.containsKey(toolName);
    }

    public List<SlashCommand> asSlashCommands() {
        return toolCallbacksByName.values()
                .stream()
                .map(ToolCallback::getToolDefinition)
                .<SlashCommand>map(toolDefinition -> new LocalToolSlashCommand(
                        toolDefinition.name(),
                        toolDefinition.name(),
                        toolDefinition.description()))
                .toList();
    }
}
