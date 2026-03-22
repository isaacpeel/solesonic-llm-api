package com.solesonic.model.prompt;

public record SlashCommandPrompt(String command,
                                 String name,
                                 String description) {
}
