package com.solesonic.model.chat;

import com.solesonic.model.prompt.SlashCommand;

import java.util.Set;
import java.util.UUID;

/**
 * @param model honoured only when a send creates its chat, so a model picked before the chat existed
 *              applies to its first turn. An existing chat changes model through
 *              {@code PUT /chats/{chatId}/model} only.
 */
public record ChatRequest(String chatMessage, Set<String> commands, Set<UUID> attachmentIds, String model) {

    public boolean namesSettingCommand() {
        return commands != null && commands.stream().anyMatch(SlashCommand.SETTING_COMMANDS::contains);
    }
}
