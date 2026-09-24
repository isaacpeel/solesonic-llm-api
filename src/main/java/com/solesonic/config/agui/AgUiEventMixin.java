package com.solesonic.config.agui;

import com.agui.community.core.event.EventType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The AG-UI SDK's event records carry no Jackson annotations, and their discriminator is a method
 * rather than a record component, so serialized as-is an event would reach the wire without the
 * {@code type} every AG-UI client dispatches on. {@code rawEvent} is the SDK's passthrough for events
 * relayed from another framework and has no meaning on this wire.
 */
@JsonPropertyOrder({"type"})
@JsonIgnoreProperties({"rawEvent"})
public interface AgUiEventMixin {

    @JsonProperty("type")
    EventType type();
}
