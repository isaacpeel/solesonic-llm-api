package com.solesonic.config;

import com.agui.community.core.agent.RunAgentInput;
import com.agui.community.core.event.CustomEvent;
import com.agui.community.core.event.RunErrorEvent;
import com.agui.community.core.event.RunStartedEvent;
import com.agui.community.core.event.TextMessageStartEvent;
import com.agui.community.core.message.Role;
import com.agui.community.core.message.ToolMessage;
import com.agui.community.core.message.UserMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgUiJacksonTest {

    private static final JsonMapper JSON_MAPPER = new JacksonConfig().jsonMapper();

    private static JsonNode serialize(Object value) {
        return JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(value));
    }

    @Test
    void eventCarriesItsTypeInTheBody() {
        JsonNode json = serialize(new RunErrorEvent("boom", null, null, null));

        assertThat(json.get("type").asString()).isEqualTo("RUN_ERROR");
        assertThat(json.get("message").asString()).isEqualTo("boom");
    }

    @Test
    void eventOmitsNullsAndTheRawEventPassthrough() {
        JsonNode json = serialize(new RunErrorEvent("boom", null, null, "ignored"));

        assertThat(json.has("rawEvent")).isFalse();
        assertThat(json.has("code")).isFalse();
        assertThat(json.has("timestamp")).isFalse();
    }

    @Test
    void runStartedNestsTheUserMessageWithItsRole() {
        RunAgentInput input = new RunAgentInput("thread-1", "run-1",
                List.of(new UserMessage("message-1", "hello")), List.of());

        JsonNode json = serialize(new RunStartedEvent("thread-1", "run-1", null, input, null, null));

        assertThat(json.get("type").asString()).isEqualTo("RUN_STARTED");
        assertThat(json.get("threadId").asString()).isEqualTo("thread-1");
        assertThat(json.get("runId").asString()).isEqualTo("run-1");

        JsonNode message = json.get("input").get("messages").get(0);
        assertThat(message.get("id").asString()).isEqualTo("message-1");
        assertThat(message.get("role").asString()).isEqualTo("user");
        assertThat(message.get("content").asString()).isEqualTo("hello");
    }

    @Test
    void roleUsesTheProtocolSpelling() {
        JsonNode json = serialize(new TextMessageStartEvent("message-1", Role.ASSISTANT, null, null));

        assertThat(json.get("role").asString()).isEqualTo("assistant");
    }

    @Test
    void customEventNestsItsValueAsAnObject() {
        JsonNode json = serialize(new CustomEvent("progress", Map.of("message", "working"), null, null));

        assertThat(json.get("type").asString()).isEqualTo("CUSTOM");
        assertThat(json.get("name").asString()).isEqualTo("progress");
        assertThat(json.get("value").get("message").asString()).isEqualTo("working");
    }

    @Test
    void toolMessageDeserializesFromTheWire() {
        String body = """
                {"id":"message-1","role":"tool","toolCallId":"call-1","content":"{\\"action\\":\\"ACCEPT\\"}"}
                """;

        ToolMessage toolMessage = JSON_MAPPER.readValue(body, ToolMessage.class);

        assertThat(toolMessage.id()).isEqualTo("message-1");
        assertThat(toolMessage.toolCallId()).isEqualTo("call-1");
        assertThat(toolMessage.content()).isEqualTo("{\"action\":\"ACCEPT\"}");
        assertThat(toolMessage.role()).isEqualTo(Role.TOOL);
    }
}
