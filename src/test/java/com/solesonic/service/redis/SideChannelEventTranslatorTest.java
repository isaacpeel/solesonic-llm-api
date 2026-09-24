package com.solesonic.service.redis;

import com.agui.community.core.event.CustomEvent;
import com.agui.community.core.event.Event;
import com.agui.community.core.event.EventType;
import com.agui.community.core.event.ToolCallArgsEvent;
import com.agui.community.core.event.ToolCallEndEvent;
import com.agui.community.core.event.ToolCallStartEvent;
import com.solesonic.config.JacksonConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SideChannelEventTranslatorTest {
    private static final String ELICITATION_ID = "5f0c3f2e-8f47-4a3c-9d0a-3f2f1d6b7c11";

    private static final String ELICITATION_JSON = """
            {"message":"Delete ticket?","requestedSchema":{"type":"object"},"elicitationId":"%s","chatId":"c1"}"""
            .formatted(ELICITATION_ID);

    private final JsonMapper jsonMapper = new JacksonConfig().jsonMapper();

    private final SideChannelEventTranslator translator = new SideChannelEventTranslator(jsonMapper);

    private static ServerSentEvent<?> forwarded(String event, String data) {
        return ServerSentEvent.builder(data).event(event).build();
    }

    @Test
    void elicitationBecomesACompleteToolCall() {
        List<Event> events = translator.translate(forwarded("elicitation", ELICITATION_JSON));

        assertThat(events).extracting(Event::type).containsExactly(
                EventType.TOOL_CALL_START, EventType.TOOL_CALL_ARGS, EventType.TOOL_CALL_END);

        ToolCallStartEvent start = (ToolCallStartEvent) events.getFirst();
        assertThat(start.toolCallId()).isEqualTo(ELICITATION_ID);
        assertThat(start.toolCallName()).isEqualTo(SideChannelEventTranslator.ELICITATION_TOOL_NAME);

        ToolCallArgsEvent args = (ToolCallArgsEvent) events.get(1);
        assertThat(args.toolCallId()).isEqualTo(ELICITATION_ID);
        assertThat(jsonMapper.readTree(args.delta())).isEqualTo(jsonMapper.readTree(ELICITATION_JSON));

        assertThat(((ToolCallEndEvent) events.get(2)).toolCallId()).isEqualTo(ELICITATION_ID);
    }

    @Test
    void progressBecomesACustomEventCarryingItsPayloadAsAnObject() {
        List<Event> events = translator.translate(forwarded("progress", "{\"message\":\"Searching\"}"));

        assertThat(events).hasSize(1);
        CustomEvent custom = (CustomEvent) events.getFirst();
        assertThat(custom.name()).isEqualTo("progress");
        assertThat(((JsonNode) custom.value()).get("message").asString()).isEqualTo("Searching");
    }

    @Test
    void attachmentAndImageKeepTheirNames() {
        assertThat(((CustomEvent) translator.translate(forwarded("attachment", "{}")).getFirst()).name())
                .isEqualTo("attachment");
        assertThat(((CustomEvent) translator.translate(forwarded("image", "{}")).getFirst()).name())
                .isEqualTo("image");
    }

    @Test
    void nonJsonPayloadIsCarriedAsText() {
        CustomEvent custom = (CustomEvent) translator.translate(forwarded("progress", "plain words")).getFirst();

        assertThat(custom.value()).isEqualTo("plain words");
    }
}
