package com.solesonic.model.prompt;

import org.a2aproject.sdk.spec.AgentCard;

public record AgentSlashCommand(String command, String name, String description) implements SlashCommand {

    public AgentSlashCommand(AgentCard agentCard) {
        this(agentCard.name(), agentCard.name(), agentCard.description());
    }
}
