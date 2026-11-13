package com.solesonic.service.chat;

import com.solesonic.model.chat.SessionTurnRecord;

import java.util.UUID;

/**
 * One turn the session advisors run, as {@link ConversationSessionService#prepareTurn} left it ready:
 * the record the advisors fill, and the two ways the turn can end.
 */
public final class ConversationTurn {
    private final ConversationSessionService conversationSessionService;
    private final UUID chatId;
    private final UUID inFlightMessageId;
    private final SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();

    ConversationTurn(ConversationSessionService conversationSessionService, UUID chatId, UUID inFlightMessageId) {
        this.conversationSessionService = conversationSessionService;
        this.chatId = chatId;
        this.inFlightMessageId = inFlightMessageId;
    }

    public UUID chatId() {
        return chatId;
    }

    public String sessionId() {
        return chatId.toString();
    }

    public SessionTurnRecord record() {
        return sessionTurnRecord;
    }

    public void complete(String text) {
        conversationSessionService.completeTurn(chatId, inFlightMessageId, sessionTurnRecord, text);
    }

    public void abandon() {
        conversationSessionService.abandonTurn(chatId, inFlightMessageId, sessionTurnRecord);
    }
}
