package com.solesonic.service.chat;

import com.solesonic.repository.chat.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Makes the model forget a chat: its session goes, and every link from its rows to it, so the next
 * turn rebuilds the session from {@code chat_message}, to the window.
 * <p>
 * The session has no foreign key to the chat, so whatever deletes a chat or one of its messages must
 * call this, inside its own transaction. It is built on repositories alone because
 * {@code ChatService} and {@code ChatMessageService} call it.
 */
@Service
public class ConversationSessionCleanup {
    private static final Logger log = LoggerFactory.getLogger(ConversationSessionCleanup.class);

    private final SessionService sessionService;
    private final ChatMessageRepository chatMessageRepository;

    public ConversationSessionCleanup(SessionService sessionService, ChatMessageRepository chatMessageRepository) {
        this.sessionService = sessionService;
        this.chatMessageRepository = chatMessageRepository;
    }

    @Transactional
    public void forget(UUID chatId) {
        sessionService.delete(chatId.toString());

        int unlinked = chatMessageRepository.unlinkSession(chatId);

        log.info("Dropped the model session of chat {}; {} message(s) to copy again", chatId, unlinked);
    }
}
