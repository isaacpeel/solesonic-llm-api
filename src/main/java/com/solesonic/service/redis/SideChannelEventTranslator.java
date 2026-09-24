package com.solesonic.service.redis;

import com.agui.community.core.event.CustomEvent;
import com.agui.community.core.event.Event;
import com.agui.community.core.event.ToolCallArgsEvent;
import com.agui.community.core.event.ToolCallEndEvent;
import com.agui.community.core.event.ToolCallStartEvent;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static com.solesonic.service.chat.events.ElicitationService.ELICITATION;
import static com.solesonic.service.chat.events.ElicitationService.ELICITATION_ID;

/**
 * Turns what the elicitation/notification pub/sub channel carries into the AG-UI frames written to
 * the durable stream.
 * <p>
 * An elicitation is presented as a tool call whose id is the elicitation id, which is what the
 * client echoes back as {@code toolCallId} when it answers. Everything else has no AG-UI
 * counterpart and goes out as {@code CUSTOM} under its existing name.
 */
@Component
public class SideChannelEventTranslator {
    public static final String ELICITATION_TOOL_NAME = "elicitation";

    private final JsonMapper jsonMapper;

    public SideChannelEventTranslator(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public List<Event> translate(ServerSentEvent<?> forwarded) {
        String eventName = forwarded.event();
        String data = String.valueOf(forwarded.data());

        if (ELICITATION.equalsIgnoreCase(eventName)) {
            return toolCall(data);
        }

        return List.of(new CustomEvent(eventName, parsedOrText(data), null, null));
    }

    private List<Event> toolCall(String elicitationJson) {
        String toolCallId = jsonMapper.readTree(elicitationJson).path(ELICITATION_ID).asString();

        return List.of(
                new ToolCallStartEvent(toolCallId, ELICITATION_TOOL_NAME, null, null, null),
                new ToolCallArgsEvent(toolCallId, elicitationJson, null, null),
                new ToolCallEndEvent(toolCallId, null, null));
    }

    private Object parsedOrText(String data) {
        try {
            JsonNode parsed = jsonMapper.readTree(data);

            return parsed.isContainer() ? parsed : data;
        } catch (JacksonException jacksonException) {
            return data;
        }
    }
}
