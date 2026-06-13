package com.solesonic.model.prompt;


import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "commandType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PromptSlashCommand.class, name = SlashCommand.PROMPT),
        @JsonSubTypes.Type(value = ToolSlashCommand.class, name = SlashCommand.TOOL),
        @JsonSubTypes.Type(value = AgentSlashCommand.class, name = SlashCommand.AGENT)
})
public sealed interface SlashCommand permits PromptSlashCommand, ToolSlashCommand, AgentSlashCommand {

    String COMMAND = "command";
    String PROMPT = "prompt";
    String TOOL = "tool";
    String AGENT = "agent";

    String command();

    String name();

    String description();
}
