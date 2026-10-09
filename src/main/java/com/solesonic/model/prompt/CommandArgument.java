package com.solesonic.model.prompt;

/**
 * The value a setting command takes, and where its valid values come from. Shaped after an MCP
 * prompt argument so prompt arguments can later drive the same picker.
 *
 * @param optionsPath context-relative, like a generated image's {@code imageUrl}; a client appends
 *                    {@code ?chatId=} when it has one
 */
public record CommandArgument(String name, String description, boolean required, String optionsPath) {
}
