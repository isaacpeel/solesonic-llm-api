package com.solesonic.config.agui;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The SDK's enums hold their protocol spelling in {@code value()} — {@code assistant}, not
 * {@code ASSISTANT} — which Jackson would otherwise ignore in favour of the constant name.
 */
public interface AgUiWireValueMixin {

    @JsonValue
    String value();
}
