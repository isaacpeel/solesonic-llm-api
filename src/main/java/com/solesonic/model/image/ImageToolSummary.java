package com.solesonic.model.image;

/**
 * The image generation tool, {@code generate_image}. The MCP server chooses the workflow itself, so
 * there is only ever one.
 *
 * @param referenceImageSlots the most reference images the tool accepts; zero when it accepts none.
 *                            Which counts are valid is decided per workflow by the MCP server
 */
public record ImageToolSummary(String name,
                               String title,
                               String description,
                               int referenceImageSlots) {
}
