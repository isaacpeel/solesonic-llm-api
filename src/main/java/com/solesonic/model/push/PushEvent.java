package com.solesonic.model.push;

import java.util.UUID;

/**
 * Something worth telling a user about while they are not looking. Each carries ids only: nothing
 * from the conversation, and no interest topic, can reach a push because nothing from them is here.
 * <p>
 * {@link #dedupeKey()} names the occurrence, so the same one is pushed at most once. A run has one
 * key whether it finished or failed, and an elicitation's reminder has its own.
 */
public sealed interface PushEvent permits PushEvent.TurnFinished,
        PushEvent.TurnFailed,
        PushEvent.ElicitationWaiting,
        PushEvent.InterestOffered,
        PushEvent.InterestUpdated,
        PushEvent.SessionInvited,
        PushEvent.SessionTurn,
        PushEvent.SessionStarted {

    /**
     * What a log line names: the chat for an event about one, the offer for an offer, which has none.
     */
    UUID subjectId();

    String dedupeKey();

    record TurnFinished(UUID chatId, UUID runId) implements PushEvent {

        @Override
        public UUID subjectId() {
            return chatId;
        }

        @Override
        public String dedupeKey() {
            return "turn:" + runId;
        }
    }

    record TurnFailed(UUID chatId, UUID runId) implements PushEvent {

        @Override
        public UUID subjectId() {
            return chatId;
        }

        @Override
        public String dedupeKey() {
            return "turn:" + runId;
        }
    }

    record ElicitationWaiting(UUID chatId, UUID elicitationId, boolean reminder) implements PushEvent {

        @Override
        public UUID subjectId() {
            return chatId;
        }

        @Override
        public String dedupeKey() {
            return reminder ? "elicitation:" + elicitationId + ":reminder" : "elicitation:" + elicitationId;
        }
    }

    record InterestOffered(UUID offerId) implements PushEvent {

        @Override
        public UUID subjectId() {
            return offerId;
        }

        @Override
        public String dedupeKey() {
            return "interest-offer:" + offerId;
        }
    }

    /**
     * A news agent created {@code chatId}. {@code updateId} is the run's id, so a retried delivery of
     * the same run is pushed once.
     */
    record InterestUpdated(UUID chatId, UUID agentId, UUID updateId) implements PushEvent {

        @Override
        public UUID subjectId() {
            return chatId;
        }

        @Override
        public String dedupeKey() {
            return "interest-update:" + updateId;
        }
    }

    record SessionInvited(UUID inviteId) implements PushEvent {

        @Override
        public UUID subjectId() {
            return inviteId;
        }

        @Override
        public String dedupeKey() {
            return "session-invite:" + inviteId;
        }
    }

    /**
     * The run {@code runId} left a shared session waiting on {@code recipientUserId}. The recipient is
     * part of the key because one run can leave the session waiting on several participants at once.
     */
    record SessionTurn(UUID chatId, UUID sessionId, UUID runId, UUID recipientUserId) implements PushEvent {

        @Override
        public UUID subjectId() {
            return sessionId;
        }

        @Override
        public String dedupeKey() {
            return "session-turn:" + runId + ":" + recipientUserId;
        }
    }

    /**
     * The opening run of a shared session finished without waiting on the inviter, who would otherwise
     * not learn the invite was accepted until it was their turn.
     */
    record SessionStarted(UUID chatId, UUID sessionId, UUID runId, UUID recipientUserId) implements PushEvent {

        @Override
        public UUID subjectId() {
            return sessionId;
        }

        @Override
        public String dedupeKey() {
            return "session-started:" + runId + ":" + recipientUserId;
        }
    }
}
