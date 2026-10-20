package com.solesonic.model.push;

import java.util.UUID;

/**
 * Something worth telling a user about while they are not looking. Each carries ids only: nothing
 * from the conversation can reach a push because nothing from it is here.
 * <p>
 * {@link #dedupeKey()} names the occurrence, so the same one is pushed at most once. A run has one
 * key whether it finished or failed, and an elicitation's reminder has its own.
 */
public sealed interface PushEvent permits PushEvent.TurnFinished, PushEvent.TurnFailed, PushEvent.ElicitationWaiting {

    UUID chatId();

    String dedupeKey();

    record TurnFinished(UUID chatId, UUID runId) implements PushEvent {

        @Override
        public String dedupeKey() {
            return "turn:" + runId;
        }
    }

    record TurnFailed(UUID chatId, UUID runId) implements PushEvent {

        @Override
        public String dedupeKey() {
            return "turn:" + runId;
        }
    }

    record ElicitationWaiting(UUID chatId, UUID elicitationId, boolean reminder) implements PushEvent {

        @Override
        public String dedupeKey() {
            return reminder ? "elicitation:" + elicitationId + ":reminder" : "elicitation:" + elicitationId;
        }
    }
}
