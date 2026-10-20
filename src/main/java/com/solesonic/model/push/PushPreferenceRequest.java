package com.solesonic.model.push;

/**
 * Boxed so that a missing field is told apart from {@code false} and refused.
 */
public record PushPreferenceRequest(Boolean turnComplete, Boolean elicitation) {
}
