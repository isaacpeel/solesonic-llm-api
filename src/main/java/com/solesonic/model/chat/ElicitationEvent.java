package com.solesonic.model.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An elicitation as the client receives it: the MCP request's own fields, plus the ids the answer
 * has to echo back. Ours rather than the SDK's because {@link McpSchema.ElicitRequest}'s type id
 * property {@code mode} collides with the records' own {@code mode()}, which Jackson refuses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ElicitationEvent(UUID elicitationId,
                               UUID chatId,
                               String mode,
                               String message,
                               Map<String, Object> requestedSchema,
                               String url,
                               @JsonProperty("_meta") Map<String, Object> meta) {
    private static final String PROPERTIES = "properties";
    private static final String FORM_MODE = "form";

    public static ElicitationEvent of(UUID chatId, UUID elicitationId, McpSchema.ElicitRequest request) {
        Map<String, Object> requestedSchema = null;
        String url = null;

        if (request instanceof McpSchema.ElicitFormRequest formRequest) {
            requestedSchema = formRequest.requestedSchema();
        }

        if (request instanceof McpSchema.ElicitUrlRequest urlRequest) {
            url = urlRequest.url();
        }

        return new ElicitationEvent(elicitationId, chatId, request.mode(), request.message(), requestedSchema, url,
                request.meta());
    }

    /**
     * A form elicitation that did not arrive as an MCP request, such as an A2A agent's prompt. It
     * reaches the client in exactly the shape {@link #of} gives an MCP form request.
     */
    public static ElicitationEvent form(UUID chatId,
                                        UUID elicitationId,
                                        String message,
                                        Map<String, Object> requestedSchema,
                                        Map<String, Object> meta) {
        return new ElicitationEvent(elicitationId, chatId, FORM_MODE, message, requestedSchema, null, meta);
    }

    /**
     * Only a form elicitation asks for values; a URL elicitation sends the user elsewhere and has
     * nothing to forward. Keyed by name with the full property schema as the value, so an accepted
     * answer can later be resolved back to the label the user picked, not just narrowed by key.
     */
    @JsonIgnore
    public Map<String, Object> requestedProperties() {
        if (requestedSchema == null || !(requestedSchema.get(PROPERTIES) instanceof Map<?, ?> properties)) {
            return Map.of();
        }

        Map<String, Object> byName = new LinkedHashMap<>();
        properties.forEach((name, schema) -> byName.put(String.valueOf(name), schema));

        return byName;
    }
}
