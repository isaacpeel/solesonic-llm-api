package com.solesonic.service.chat.events;

import tools.jackson.databind.JsonNode;

/**
 * One message on a chat's side channel: progress, attachment and image notifications, an
 * elicitation, or the cancel signal. {@code data} stays a tree from the one parse it gets on
 * arrival until it is written into an AG-UI frame.
 */
public record SideChannelMessage(String event, JsonNode data) {
}
