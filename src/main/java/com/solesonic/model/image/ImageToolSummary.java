package com.solesonic.model.image;

/**
 * One image generation tool a client can choose on {@code POST /images}. Each is a stored ComfyUI
 * workflow on the MCP server, so choosing a tool is choosing a workflow.
 *
 * @param referenceImageSlots exactly how many reference images the tool takes when given any;
 *                            zero when it accepts none
 */
public record ImageToolSummary(String name,
                               String title,
                               String description,
                               int referenceImageSlots) {
}
