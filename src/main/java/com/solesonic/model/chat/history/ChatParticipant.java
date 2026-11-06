package com.solesonic.model.chat.history;

import java.util.UUID;

/**
 * Someone seated in a shared chat, as the history list shows them: the name the room knows them by.
 */
public record ChatParticipant(UUID userId, String name) {
}
