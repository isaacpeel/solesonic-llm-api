package com.solesonic.config.agui;

import com.agui.community.core.message.Role;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Like {@link AgUiEventMixin}: a message's {@code role} is a method on the SDK records, not a
 * component, and AG-UI clients discriminate messages on it.
 */
@JsonPropertyOrder({"id", "role"})
public interface AgUiMessageMixin {

    @JsonProperty("role")
    Role role();
}
