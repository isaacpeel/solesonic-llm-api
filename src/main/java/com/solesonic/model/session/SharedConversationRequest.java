package com.solesonic.model.session;

import java.util.List;
import java.util.UUID;

/**
 * Shares a chat: {@code sourceChatId} is the private chat to fork, or null to start from an empty one.
 * {@code name} is optional and defaults to the source chat's name.
 */
public record SharedConversationRequest(UUID sourceChatId, List<String> usernames, String name) {
}
