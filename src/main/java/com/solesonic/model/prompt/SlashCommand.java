package com.solesonic.model.prompt;


import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "commandType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PromptSlashCommand.class, name = SlashCommand.PROMPT),
        @JsonSubTypes.Type(value = ToolSlashCommand.class, name = SlashCommand.TOOL),
        @JsonSubTypes.Type(value = AgentSlashCommand.class, name = SlashCommand.AGENT),
        @JsonSubTypes.Type(value = LocalToolSlashCommand.class, name = SlashCommand.LOCAL_TOOL)
})
public sealed interface SlashCommand permits PromptSlashCommand, ToolSlashCommand, AgentSlashCommand, LocalToolSlashCommand {

    String COMMAND = "command";
    String PROMPT = "prompt";
    String TOOL = "tool";
    String AGENT = "agent";
    String LOCAL_TOOL = "local-tool";

    String command();

    String name();

    String description();
}
