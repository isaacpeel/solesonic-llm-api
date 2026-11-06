package com.solesonic.model.a2a;

import java.util.Map;

/**
 * A structured question an A2A agent raised through {@link A2AElicitationExtension}, in the shape of
 * an MCP form elicitation.
 */
public record A2APrompt(String message, Map<String, Object> requestedSchema, Map<String, Object> meta) {
}
