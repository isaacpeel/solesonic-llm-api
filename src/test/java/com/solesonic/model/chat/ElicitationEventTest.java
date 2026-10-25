package com.solesonic.model.chat;

import com.solesonic.config.JacksonConfig;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ElicitationEventTest {
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID ELICITATION_ID = UUID.randomUUID();

    private final JsonMapper jsonMapper = new JacksonConfig().jsonMapper();

    @Test
    void formRequestCarriesBothIdsTheSchemaAndMetaUnderItsWireName() {
        McpSchema.ElicitFormRequest request = new McpSchema.ElicitFormRequest("Who should this be assigned to?",
                Map.of("type", "object"), Map.of("chatId", CHAT_ID.toString()));

        JsonNode json = jsonMapper.valueToTree(ElicitationEvent.of(CHAT_ID, ELICITATION_ID, request));

        assertThat(json.path("elicitationId").asString()).isEqualTo(ELICITATION_ID.toString());
        assertThat(json.path("chatId").asString()).isEqualTo(CHAT_ID.toString());
        assertThat(json.path("mode").asString()).isEqualTo(request.mode());
        assertThat(json.path("message").asString()).isEqualTo("Who should this be assigned to?");
        assertThat(json.path("requestedSchema").path("type").asString()).isEqualTo("object");
        assertThat(json.path("_meta").path("chatId").asString()).isEqualTo(CHAT_ID.toString());
        assertThat(json.has("url")).isFalse();
        assertThat(json.has("meta")).isFalse();
    }

    @Test
    void urlRequestCarriesTheUrlAndNoSchema() {
        McpSchema.ElicitUrlRequest request = new McpSchema.ElicitUrlRequest("Sign in", "https://example.com/login",
                "upstream-id", null);

        JsonNode json = jsonMapper.valueToTree(ElicitationEvent.of(CHAT_ID, ELICITATION_ID, request));

        assertThat(json.path("url").asString()).isEqualTo("https://example.com/login");
        assertThat(json.path("elicitationId").asString()).isEqualTo(ELICITATION_ID.toString());
        assertThat(json.has("requestedSchema")).isFalse();
        assertThat(json.has("_meta")).isFalse();
    }

    @Test
    void readsBackFromItsOwnJson() {
        McpSchema.ElicitFormRequest request = new McpSchema.ElicitFormRequest("Sure?", Map.of("type", "object"),
                Map.of("chatId", CHAT_ID.toString()));
        ElicitationEvent event = ElicitationEvent.of(CHAT_ID, ELICITATION_ID, request);

        assertThat(jsonMapper.treeToValue(jsonMapper.valueToTree(event), ElicitationEvent.class)).isEqualTo(event);
    }

    @Test
    void requestedPropertiesAreTheSchemasPropertiesByName() {
        Map<String, Object> assignee = Map.of("type", "string");
        McpSchema.ElicitFormRequest request = new McpSchema.ElicitFormRequest("Who?",
                Map.of("type", "object", "properties", Map.of("assigneeAccountId", assignee)), null);

        assertThat(ElicitationEvent.of(CHAT_ID, ELICITATION_ID, request).requestedProperties())
                .containsExactly(Map.entry("assigneeAccountId", assignee));
    }

    @Test
    void requestedPropertiesAreEmptyWithoutASchemaOrForAUrlRequest() {
        McpSchema.ElicitFormRequest noProperties = new McpSchema.ElicitFormRequest("Sure?", Map.of("type", "object"), null);
        McpSchema.ElicitUrlRequest urlRequest = new McpSchema.ElicitUrlRequest("Sign in", "https://example.com", "id", null);

        assertThat(ElicitationEvent.of(CHAT_ID, ELICITATION_ID, noProperties).requestedProperties()).isEmpty();
        assertThat(ElicitationEvent.of(CHAT_ID, ELICITATION_ID, urlRequest).requestedProperties()).isEmpty();
    }
}
