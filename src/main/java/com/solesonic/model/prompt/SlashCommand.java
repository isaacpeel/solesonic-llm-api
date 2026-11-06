package com.solesonic.model.prompt;


import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Set;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "commandType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PromptSlashCommand.class, name = SlashCommand.PROMPT),
        @JsonSubTypes.Type(value = ToolSlashCommand.class, name = SlashCommand.TOOL),
        @JsonSubTypes.Type(value = AgentSlashCommand.class, name = SlashCommand.AGENT),
        @JsonSubTypes.Type(value = LocalToolSlashCommand.class, name = SlashCommand.LOCAL_TOOL),
        @JsonSubTypes.Type(value = ModelSlashCommand.class, name = SlashCommand.MODEL)
})
public sealed interface SlashCommand permits PromptSlashCommand, ToolSlashCommand, AgentSlashCommand, LocalToolSlashCommand,
        ModelSlashCommand {

    String COMMAND = "command";
    String PROMPT = "prompt";
    String TOOL = "tool";
    String AGENT = "agent";
    String LOCAL_TOOL = "local-tool";
    String MODEL = "model";

    /**
     * Commands the client applies itself through REST. They are listed for typeahead but never sent
     * as a chat message, so they never resolve for routing.
     */
    Set<String> SETTING_COMMANDS = Set.of(MODEL);

    String command();

    String name();

    String description();
}
