package com.solesonic.model.prompt;

import io.a2a.spec.AgentCard;

public record AgentSlashCommand(String command, String name, String description) implements SlashCommand {

    public AgentSlashCommand(AgentCard agentCard) {
        this(agentCard.name(), agentCard.name(), agentCard.description());
    }
}
