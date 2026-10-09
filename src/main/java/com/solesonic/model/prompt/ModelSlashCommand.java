package com.solesonic.model.prompt;

/**
 * The built-in {@code /model} command. Unlike every other variant it is not sourced from the MCP
 * server: {@code SlashCommandService} adds it to the catalog on every read.
 * <p>
 * It is a setting command: listed for typeahead, applied by the client through
 * {@code PUT /chats/{chatId}/model}, and never sent as a chat message.
 */
public record ModelSlashCommand(String command, String name, String description, CommandArgument argument)
        implements SlashCommand {

    public ModelSlashCommand() {
        this(MODEL, MODEL, "Choose the model this chat uses for its next messages",
                new CommandArgument(MODEL, "The chat model to use", true, "/chats/models"));
    }
}
