package com.solesonic.service.redis;

import com.solesonic.model.chat.ChatRequest;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Everything one run needs, for a route that is not a conversation's own send.
 *
 * @param streamUserId whose {@code (chatId, userId)} stream key the frames go to: the chat's owner,
 *                     who in a shared session is not always the sender
 * @param senderUserId who sent the message: the user message is attributed to them, and the
 *                     active stream is tracked for them
 * @param senderName   the sender's name on the {@code RUN_STARTED} user message, or null
 * @param chunks       the text the run streams
 */
public record TurnPlan(UUID runId,
                       UUID chatId,
                       UUID streamUserId,
                       UUID senderUserId,
                       String senderName,
                       ChatRequest chatRequest,
                       Supplier<Flux<String>> chunks,
                       TurnHooks hooks) {
}
