package com.solesonic.model.prompt;

public record LocalToolSlashCommand(String command, String name, String description) implements SlashCommand {
}
