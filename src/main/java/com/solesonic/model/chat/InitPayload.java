package com.solesonic.model.chat;

import java.util.UUID;

/**
 * Payload of the SSE {@code init} event. Carries the id of the freshly persisted user message so
 * the client can associate its outgoing bubble — and any attachments it uploaded — with a real row.
 */
public record InitPayload(UUID chatId, UUID messageId) {
}
