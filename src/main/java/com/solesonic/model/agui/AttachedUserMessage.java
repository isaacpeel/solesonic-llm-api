package com.solesonic.model.agui;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.solesonic.model.chat.attachment.ChatAttachmentSummary;

import java.util.List;
import java.util.UUID;

/**
 * The user message a shared conversation's {@code RUN_STARTED} frame echoes, and that frame's {@code input}.
 * <p>
 * The SDK's {@code UserMessage} carries text and a name only, and its {@code Message} type is sealed, so
 * this is a record of its own with the same wire shape plus what the room needs while the run is live:
 * what the sender attached ({@code attachments}, the summaries the transcript carries), the commands they
 * used, and their id, so a client tells senders apart by id rather than by a display name two people may
 * share. {@code RunStartedEvent.input} is an {@code Object}, which is what lets the frame carry it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "role"})
public record AttachedUserMessage(String id,
                                  String content,
                                  String name,
                                  UUID senderUserId,
                                  List<ChatAttachmentSummary> attachments,
                                  List<String> commands) {

    @JsonProperty("role")
    public String role() {
        return "user";
    }

    /**
     * The run input around it, shaped like the SDK's {@code RunAgentInput}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Input(String threadId, String runId, List<AttachedUserMessage> messages, List<Object> tools) {
    }
}
