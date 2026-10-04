package com.solesonic.service.image;

import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.mcp.client.McpIdentityProvider;
import com.solesonic.model.image.ImageToolSummary;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.solesonic.model.image.ImageGenerationErrorCode.IMAGE_TOOL_REQUIRED;
import static com.solesonic.model.image.ImageGenerationErrorCode.INVALID_IMAGE_TOOL;
import static com.solesonic.model.image.ImageGenerationErrorCode.NO_IMAGE_TOOLS;

/**
 * The image generation tools, exactly as the MCP server offers them.
 * <p>
 * The set is entirely the MCP server's: one tool per enabled workflow row, each tagged
 * {@code _meta: {"solesonic/kind": "image-generation"}}. Nothing here names a tool. With no
 * workflows configured there are no image tools, and image generation is simply unavailable.
 * <p>
 * The set is the MCP catalog as listed at startup, the same snapshot every chat route uses, so a
 * workflow added on the MCP server appears after this API restarts.
 */
@Service
public class ImageToolCatalog {
    private static final Logger log = LoggerFactory.getLogger(ImageToolCatalog.class);

    private final McpIdentityProvider mcpIdentityProvider;
    private final ReferenceImageInjector referenceImageInjector;

    public ImageToolCatalog(McpIdentityProvider mcpIdentityProvider, ReferenceImageInjector referenceImageInjector) {
        this.mcpIdentityProvider = mcpIdentityProvider;
        this.referenceImageInjector = referenceImageInjector;

        log.info("Image tools in the MCP catalog: {}", toolNames());
    }

    public List<ImageToolSummary> summaries() {
        return imageTools().stream()
                .map(tool -> new ImageToolSummary(
                        tool.name(),
                        StringUtils.defaultIfBlank(tool.title(), tool.name()),
                        tool.description(),
                        referenceImageInjector.referenceSlots(tool.inputSchema())))
                .toList();
    }

    public Set<String> toolNames() {
        Set<String> toolNames = new LinkedHashSet<>();
        imageTools().forEach(tool -> toolNames.add(tool.name()));

        return toolNames;
    }

    /**
     * The tool a request should call.
     * <p>
     * A request may leave the tool out only when there is exactly one to choose; with several, the
     * caller has to say which. There is deliberately no configured default — which tools exist is
     * the MCP server's business alone.
     *
     * @param requestedTool the tool the caller named, or blank
     * @throws ImageGenerationException {@code NO_IMAGE_TOOLS} when no workflow is configured,
     *                                  {@code IMAGE_TOOL_REQUIRED} when the choice is ambiguous, and
     *                                  {@code INVALID_IMAGE_TOOL} when the name is not an image tool —
     *                                  the endpoint must not become a way to call an arbitrary MCP
     *                                  tool on the user's token
     */
    public McpSchema.Tool resolve(String requestedTool) {
        List<McpSchema.Tool> imageTools = imageTools();

        if (imageTools.isEmpty()) {
            throw new ImageGenerationException(NO_IMAGE_TOOLS, "Image generation is not configured.");
        }

        if (StringUtils.isBlank(requestedTool)) {
            if (imageTools.size() == 1) {
                return imageTools.getFirst();
            }

            throw new ImageGenerationException(IMAGE_TOOL_REQUIRED, "Choose which image style to use.");
        }

        String toolName = requestedTool.trim();

        return imageTools.stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("Refusing image generation with unknown image tool '{}'", toolName);

                    return new ImageGenerationException(INVALID_IMAGE_TOOL, "That image style is not available.");
                });
    }

    private List<McpSchema.Tool> imageTools() {
        return mcpIdentityProvider.tools().stream()
                .filter(GeneratedImageToolInterceptor::isTaggedImageTool)
                .toList();
    }
}
