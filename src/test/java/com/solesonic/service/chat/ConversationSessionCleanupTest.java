package com.solesonic.service.chat;

import com.solesonic.repository.chat.ChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConversationSessionCleanupTest {

    private final SessionService sessionService = DefaultSessionService.builder()
            .sessionRepository(InMemorySessionRepository.builder().build())
            .build();

    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);

    private final ConversationSessionCleanup conversationSessionCleanup =
            new ConversationSessionCleanup(sessionService, chatMessageRepository);

    @Test
    void forgetDeletesTheSessionAndEveryLinkToIt() {
        UUID chatId = UUID.randomUUID();
        sessionService.create(CreateSessionRequest.builder().id(chatId.toString()).build());
        sessionService.appendMessage(chatId.toString(), new UserMessage("remember me"));

        conversationSessionCleanup.forget(chatId);

        assertThat(sessionService.findById(chatId.toString())).isNull();
        verify(chatMessageRepository).unlinkSession(chatId);
    }

    /**
     * A chat that never reached the model has no session; forgetting it must not fail the delete it
     * runs inside.
     */
    @Test
    void forgetOfAChatWithNoSessionStillUnlinks() {
        UUID chatId = UUID.randomUUID();

        conversationSessionCleanup.forget(chatId);

        verify(chatMessageRepository).unlinkSession(chatId);
    }
}
