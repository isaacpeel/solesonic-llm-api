package com.solesonic.model.a2a;

import com.solesonic.config.JacksonConfig;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class A2AElicitationExtensionTest {
    private static final UUID CHAT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JsonMapper jsonMapper = new JacksonConfig().jsonMapper();

    private static Map<String, Object> requestedSchema() {
        return Map.of("type", "object",
                "properties", Map.of("choice", Map.of("type", "string", "title", "Play or pass",
                        "oneOf", List.of(Map.of("const", "play", "title", "Play"), Map.of("const", "pass", "title", "Pass")))),
                "required", List.of("choice"));
    }

    private static Map<String, Object> elicitation(String mode, String message, Map<String, Object> requestedSchema) {
        Map<String, Object> elicitation = new LinkedHashMap<>();
        elicitation.put("mode", mode);
        elicitation.put("message", message);
        elicitation.put("requestedSchema", requestedSchema);
        elicitation.put("_meta", Map.of("solesonic/elicitation", "feud", "feud/prompt", "play_or_pass"));

        return elicitation;
    }

    private static Message statusMessage(Part<?>... parts) {
        return Message.builder()
                .role(Message.Role.ROLE_AGENT)
                .messageId("status-1")
                .parts(parts)
                .build();
    }

    private static Message promptMessage(Map<String, Object> elicitation) {
        return statusMessage(new DataPart(Map.of("elicitation", elicitation)));
    }

    @Test
    void readsAFormPrompt() {
        Message message = promptMessage(elicitation("form", "Alex, play or pass?", requestedSchema()));

        assertThat(A2AElicitationExtension.prompt(message, jsonMapper)).hasValueSatisfying(prompt -> {
            assertThat(prompt.message()).isEqualTo("Alex, play or pass?");
            assertThat(prompt.requestedSchema()).isEqualTo(requestedSchema());
            assertThat(prompt.meta()).containsEntry("feud/prompt", "play_or_pass");
        });
    }

    @Test
    void findsThePromptAmongOtherParts() {
        Message message = statusMessage(new TextPart("board"), new DataPart(Map.of("score", 12)),
                new DataPart(Map.of("elicitation", elicitation("form", "Ready?", requestedSchema()))));

        assertThat(A2AElicitationExtension.prompt(message, jsonMapper))
                .hasValueSatisfying(prompt -> assertThat(prompt.message()).isEqualTo("Ready?"));
    }

    @Test
    void aTextOnlyStatusHasNoPrompt() {
        assertThat(A2AElicitationExtension.prompt(statusMessage(new TextPart("What priority?")), jsonMapper)).isEmpty();
    }

    @Test
    void aUrlModePromptIsRefused() {
        assertThat(A2AElicitationExtension.prompt(promptMessage(elicitation("url", "Sign in", requestedSchema())), jsonMapper))
                .isEmpty();
    }

    @Test
    void aBlankMessageIsRefused() {
        assertThat(A2AElicitationExtension.prompt(promptMessage(elicitation("form", " ", requestedSchema())), jsonMapper))
                .isEmpty();
    }

    @Test
    void aSchemaThatIsNotAnObjectOfPropertiesIsRefused() {
        Map<String, Object> arraySchema = Map.of("type", "array", "properties", Map.of());
        Map<String, Object> noProperties = Map.of("type", "object", "properties", List.of("choice"));
        Map<String, Object> emptyProperties = Map.of("type", "object", "properties", Map.of());

        assertThat(A2AElicitationExtension.prompt(promptMessage(elicitation("form", "Pick", emptyProperties)), jsonMapper))
                .isEmpty();

        assertThat(A2AElicitationExtension.prompt(promptMessage(elicitation("form", "Pick", arraySchema)), jsonMapper))
                .isEmpty();
        assertThat(A2AElicitationExtension.prompt(promptMessage(elicitation("form", "Pick", noProperties)), jsonMapper))
                .isEmpty();
    }

    @Test
    void anOversizedPromptIsRefused() {
        String oversized = "x".repeat(A2AElicitationExtension.MAX_PROMPT_BYTES);

        assertThat(A2AElicitationExtension.prompt(promptMessage(elicitation("form", oversized, requestedSchema())), jsonMapper))
                .isEmpty();
    }

    @Test
    void anElicitationThatIsNotAnObjectIsRefused() {
        assertThat(A2AElicitationExtension.prompt(statusMessage(new DataPart(Map.of("elicitation", "play or pass"))), jsonMapper))
                .isEmpty();
    }

    @Test
    void theAnswerContinuesTheTaskWithOneDataPart() {
        Message answer = A2AElicitationExtension.answer(CHAT_ID, "task-1", Map.of("choice", "play"));

        assertThat(answer.role()).isEqualTo(Message.Role.ROLE_USER);
        assertThat(answer.taskId()).isEqualTo("task-1");
        assertThat(answer.contextId()).isEqualTo(CHAT_ID.toString());
        assertThat(answer.messageId()).isNotBlank();
        assertThat(answer.extensions()).containsExactly(A2AElicitationExtension.URI);
        assertThat(answer.parts()).singleElement().isInstanceOf(DataPart.class);

        JsonNode data = jsonMapper.valueToTree(((DataPart) answer.parts().getFirst()).data());
        assertThat(data).isEqualTo(jsonMapper.readTree(
                "{\"elicitationResponse\":{\"action\":\"accept\",\"content\":{\"choice\":\"play\"}}}"));
    }
}
