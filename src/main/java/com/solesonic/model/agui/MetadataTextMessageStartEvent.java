package com.solesonic.model.agui;

import com.agui.community.core.event.EventType;
import com.agui.community.core.message.Role;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Map;

/**
 * {@code TEXT_MESSAGE_START} carrying AG-UI 1.0's {@code metadata}, which java-core 0.1.1 predates.
 * The SDK's {@code Event} is sealed, so this is a plain record that writes the same frame as the
 * SDK's {@code TextMessageStartEvent}, {@code type} first, plus that field. Retire it once java-core
 * gains {@code metadata}.
 */
@JsonPropertyOrder({"type"})
public record MetadataTextMessageStartEvent(String messageId,
                                            Role role,
                                            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> metadata) {

    @JsonProperty("type")
    public EventType type() {
        return EventType.TEXT_MESSAGE_START;
    }
}
