package com.solesonic.service.redis;

import com.agui.community.core.event.CustomEvent;
import com.agui.community.core.event.Event;
import com.agui.community.core.event.ToolCallArgsEvent;
import com.agui.community.core.event.ToolCallEndEvent;
import com.agui.community.core.event.ToolCallStartEvent;
import com.solesonic.model.chat.ElicitationEvent;
import com.solesonic.service.chat.events.SideChannelMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static com.solesonic.service.chat.events.ElicitationService.ELICITATION;

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

    public List<Event> translate(SideChannelMessage message) {
        if (ELICITATION.equalsIgnoreCase(message.event())) {
            return toolCall(message.data());
        }

        return List.of(new CustomEvent(message.event(), message.data(), null, null));
    }

    /**
     * The arguments are written back to a string only because AG-UI defines a tool call's delta as
     * one.
     */
    private List<Event> toolCall(JsonNode elicitation) {
        String toolCallId = jsonMapper.treeToValue(elicitation, ElicitationEvent.class).elicitationId().toString();

        return List.of(
                new ToolCallStartEvent(toolCallId, ELICITATION_TOOL_NAME, null, null, null),
                new ToolCallArgsEvent(toolCallId, jsonMapper.writeValueAsString(elicitation), null, null),
                new ToolCallEndEvent(toolCallId, null, null));
    }
}
