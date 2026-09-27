package com.solesonic.service.chat;

import com.solesonic.model.chat.history.Chat;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.image.GeneratedImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatAttachmentService chatAttachmentService;

    @Mock
    private GeneratedImageService generatedImageService;

    @Mock
    private A2AStickyAgentService a2aStickyAgentService;

    @Mock
    private RedisStreamService redisStreamService;

    private final UserRequestContext userRequestContext = new UserRequestContext();

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        userRequestContext.setUserId(USER_ID);

        chatService = new ChatService(
                chatRepository,
                chatMessageRepository,
                chatAttachmentService,
                generatedImageService,
                a2aStickyAgentService,
                redisStreamService,
                userRequestContext);
    }

    @Test
    void returnsAnOwnedChatWithItsMessages() {
        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        chat.setUserId(USER_ID);

        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));
        when(chatMessageRepository.findByChatId(CHAT_ID)).thenReturn(List.of());
        when(chatAttachmentService.forChat(CHAT_ID)).thenReturn(List.of());
        when(generatedImageService.forChat(CHAT_ID)).thenReturn(List.of());

        assertThat(chatService.get(CHAT_ID).getId()).isEqualTo(CHAT_ID);
    }

    /**
     * Without this, any authenticated caller who knew or guessed another user's {@code chatId}
     * could read that chat's full message history.
     */
    @Test
    void reportsAChatOwnedBySomeoneElseAsNotFound() {
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.get(CHAT_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(NOT_FOUND);
    }
}
