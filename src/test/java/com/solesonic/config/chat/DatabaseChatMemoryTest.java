package com.solesonic.config.chat;

import com.solesonic.model.session.SessionMode;
import com.solesonic.model.session.SessionParticipant;
import com.solesonic.model.session.SharedSession;
import com.solesonic.repository.session.SessionParticipantRepository;
import com.solesonic.repository.session.SharedSessionRepository;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.image.GeneratedImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseChatMemoryTest {
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID SAM = UUID.randomUUID();

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private GeneratedImageService generatedImageService;

    @Mock
    private SharedSessionRepository sharedSessionRepository;

    @Mock
    private SessionParticipantRepository sessionParticipantRepository;

    private DatabaseChatMemory databaseChatMemory;

    private final List<Message> plain = List.of(new UserMessage("hello"), new AssistantMessage("hi"));
    private final List<Message> attributed = List.of(new UserMessage("Alex: hello"), new AssistantMessage("hi"));

    @BeforeEach
    void setUp() {
        databaseChatMemory = new DatabaseChatMemory(chatMessageService, generatedImageService, sharedSessionRepository,
                sessionParticipantRepository);
    }

    private static SessionParticipant participant(UUID userId, String name, boolean left) {
        SessionParticipant participant = new SessionParticipant();
        participant.setSessionId(SESSION_ID);
        participant.setUserId(userId);
        participant.setName(name);
        participant.setLeftAt(left ? ZonedDateTime.now() : null);

        return participant;
    }

    @Test
    void aConversationSessionsHistoryIsAttributedByParticipantName() {
        SharedSession sharedSession = new SharedSession();
        sharedSession.setId(SESSION_ID);
        sharedSession.setMode(SessionMode.CONVERSATION);

        when(sharedSessionRepository.findByChatIdAndMode(CHAT_ID, SessionMode.CONVERSATION)).thenReturn(Optional.of(sharedSession));
        when(sessionParticipantRepository.findBySessionIdOrderBySeatAsc(SESSION_ID))
                .thenReturn(List.of(participant(ALEX, "Alex", false), participant(SAM, "Sam", true)));
        when(chatMessageService.findByChatId(CHAT_ID, Map.of(ALEX, "Alex", SAM, "Sam"))).thenReturn(attributed);

        assertThat(databaseChatMemory.get(CHAT_ID.toString())).isEqualTo(attributed);
    }

    @Test
    void aPrivateChatsHistoryIsUnchanged() {
        when(sharedSessionRepository.findByChatIdAndMode(CHAT_ID, SessionMode.CONVERSATION)).thenReturn(Optional.empty());
        when(chatMessageService.findByChatId(CHAT_ID)).thenReturn(plain);

        assertThat(databaseChatMemory.get(CHAT_ID.toString())).isEqualTo(plain);
        verify(chatMessageService, never()).findByChatId(any(UUID.class), any());
        verifyNoInteractions(sessionParticipantRepository);
    }
}
