package com.solesonic.service.image;

import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.mcp.client.McpIdentityProvider;
import com.solesonic.model.image.ImageToolSummary;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static com.solesonic.model.image.ImageGenerationErrorCode.NO_IMAGE_TOOLS;

/**
 * The image generation tool, exactly as the MCP server offers it.
 * <p>
 * There is one: {@code generate_image}, found by name. The MCP server chooses the workflow from the
 * request itself, so there is nothing for a caller to choose here. Without it image generation is
 * unavailable.
 * <p>
 * The tool is read from the MCP catalog as listed at startup, the same snapshot every chat route
 * uses.
 */
@Service
public class ImageToolCatalog {
    private static final Logger log = LoggerFactory.getLogger(ImageToolCatalog.class);

    private final McpIdentityProvider mcpIdentityProvider;
    private final ReferenceImageInjector referenceImageInjector;

    public ImageToolCatalog(McpIdentityProvider mcpIdentityProvider, ReferenceImageInjector referenceImageInjector) {
        this.mcpIdentityProvider = mcpIdentityProvider;
        this.referenceImageInjector = referenceImageInjector;

        log.info("Image tool in the MCP catalog: {}", imageTool().isPresent());
    }

    public List<ImageToolSummary> summaries() {
        return imageTool().stream()
                .map(tool -> new ImageToolSummary(
                        tool.name(),
                        StringUtils.defaultIfBlank(tool.title(), tool.name()),
                        tool.description(),
                        referenceImageInjector.referenceSlots(tool.inputSchema())))
                .toList();
    }

    /**
     * @throws ImageGenerationException {@code NO_IMAGE_TOOLS} when the MCP server does not list
     *                                  {@code generate_image}
     */
    public McpSchema.Tool resolve() {
        return imageTool().orElseThrow(() ->
                new ImageGenerationException(NO_IMAGE_TOOLS, "Image generation is not configured."));
    }

    private Optional<McpSchema.Tool> imageTool() {
        return mcpIdentityProvider.tools().stream()
                .filter(GeneratedImageToolInterceptor::isImageTool)
                .findFirst();
    }
}
