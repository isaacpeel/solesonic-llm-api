package com.solesonic.service.ollama;

import com.solesonic.model.chat.history.Chat;
import com.solesonic.repository.ollama.ChatMessageRepository;
import com.solesonic.repository.ollama.ChatRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.image.GeneratedImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards {@link ChatService#rename(UUID, String)}: the caller's identity comes only from
 * {@link UserRequestContext}, which is resolved from the JWT subject, never from a request
 * parameter — so a chat owned by someone else must be indistinguishable from one that does not
 * exist.
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private ChatRepository chatRepository;

    @Mock
    @SuppressWarnings("unused")
    private ChatMessageRepository chatMessageRepository;

    @Mock
    @SuppressWarnings("unused")
    private ChatAttachmentService chatAttachmentService;

    @Mock
    @SuppressWarnings("unused")
    private GeneratedImageService generatedImageService;

    @Mock
    private UserRequestContext userRequestContext;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(
                chatRepository, chatMessageRepository, chatAttachmentService, generatedImageService, userRequestContext);
    }

    private Chat chatOwnedBy(UUID ownerId) {
        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        chat.setUserId(ownerId);

        return chat;
    }

    @Test
    void renamesAChatOwnedByTheCaller() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chatOwnedBy(USER_ID)));
        when(chatRepository.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Chat renamed = chatService.rename(CHAT_ID, "Trip planning");

        assertThat(renamed.getName()).isEqualTo("Trip planning");
        verify(chatRepository).save(renamed);
    }

    @Test
    void trimsTheName() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chatOwnedBy(USER_ID)));
        when(chatRepository.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Chat renamed = chatService.rename(CHAT_ID, "  Trip planning  ");

        assertThat(renamed.getName()).isEqualTo("Trip planning");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> chatService.rename(CHAT_ID, "   "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        verify(chatRepository, never()).findByIdAndUserId(any(), any());
    }

    @Test
    void rejectsANameOverTheLengthLimit() {
        String tooLong = "x".repeat(256);

        assertThatThrownBy(() -> chatService.rename(CHAT_ID, tooLong))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        verify(chatRepository, never()).findByIdAndUserId(any(), any());
    }

    /**
     * A chat owned by another user must answer identically to one that does not exist —
     * {@link ChatRepository#findByIdAndUserId} enforces that at the query, so there is nothing
     * further to distinguish here.
     */
    @Test
    void reportsAChatNotOwnedByTheCallerAsNotFound() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.rename(CHAT_ID, "Trip planning"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
