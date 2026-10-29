package com.solesonic.service.chat;

import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.image.GeneratedImageService;
import com.solesonic.service.interest.InterestUpdateService;
import com.solesonic.service.memory.UserMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final Pageable PAGE = PageRequest.of(0, 20);

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

    @Mock
    private ChatModelSelection chatModelSelection;

    @Mock
    private UserMemoryService userMemoryService;

    @Mock
    private InterestUpdateService interestUpdateService;

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
                chatModelSelection,
                userMemoryService,
                interestUpdateService,
                userRequestContext);
    }

    /**
     * Deleting a conversation is a request to forget it, so the cascade includes everything learned
     * from it, for the caller and nobody else.
     */
    @Test
    void deletingAChatForgetsWhatWasLearnedFromIt() {
        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        chat.setUserId(USER_ID);

        when(chatRepository.lockByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));
        when(a2aStickyAgentService.deactivate(CHAT_ID)).thenReturn(Mono.empty());
        when(a2aStickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());
        when(redisStreamService.deleteStream(CHAT_ID, USER_ID)).thenReturn(Mono.just(true));
        when(chatModelSelection.clear(CHAT_ID)).thenReturn(Mono.empty());

        chatService.delete(CHAT_ID);

        verify(userMemoryService).forgetChat(CHAT_ID, USER_ID);
        verify(interestUpdateService).deleteForChat(CHAT_ID, USER_ID);
    }

    @Test
    void deletingAChatTheCallerDoesNotOwnForgetsNothing() {
        when(chatRepository.lockByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.delete(CHAT_ID)).isInstanceOf(ResponseStatusException.class);

        verify(userMemoryService, never()).forgetChat(any(), any());
        verify(interestUpdateService, never()).deleteForChat(any(), any());
    }

    /**
     * The model selection is the one key a chat leaves in Redis with no expiry, so deleting the chat
     * is the only thing that ever removes it.
     */
    @Test
    void deletingAChatClearsItsModelSelection() {
        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        chat.setUserId(USER_ID);

        when(chatRepository.lockByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));
        when(a2aStickyAgentService.deactivate(CHAT_ID)).thenReturn(Mono.empty());
        when(a2aStickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());
        when(redisStreamService.deleteStream(CHAT_ID, USER_ID)).thenReturn(Mono.just(true));
        when(chatModelSelection.clear(CHAT_ID)).thenReturn(Mono.empty());

        chatService.delete(CHAT_ID);

        verify(chatModelSelection).clear(CHAT_ID);
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

    @Test
    void searchRejectsAQueryOfOneCharacterOnceTrimmed() {
        assertThatThrownBy(() -> chatService.searchByUserId(USER_ID, "  a  ", PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(BAD_REQUEST);

        verify(chatRepository, never()).searchByUserId(any(), any(), any(), any());
    }

    @Test
    void searchRejectsAQueryLongerThanTwoHundredCharacters() {
        assertThatThrownBy(() -> chatService.searchUngroupedByUserId(USER_ID, "a".repeat(201), PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(BAD_REQUEST);

        verify(chatRepository, never()).searchUngroupedByUserId(any(), any(), any(), any());
    }

    @Test
    void searchAcceptsTwoAndTwoHundredCharacters() {
        when(chatRepository.searchByUserId(eq(USER_ID), any(), any(), eq(PAGE))).thenReturn(Page.empty(PAGE));

        assertThat(chatService.searchByUserId(USER_ID, "ab", PAGE)).isEmpty();
        assertThat(chatService.searchByUserId(USER_ID, "a".repeat(200), PAGE)).isEmpty();
    }

    @Test
    void searchTrimsLowercasesAndEscapesTheLikePattern() {
        when(chatRepository.searchByUserId(eq(USER_ID), any(), any(), eq(PAGE))).thenReturn(Page.empty(PAGE));

        chatService.searchByUserId(USER_ID, "  50%_OFF\\now  ", PAGE);

        verify(chatRepository).searchByUserId(USER_ID, "%50\\%\\_off\\\\now%", ChatService.SEARCHABLE_MESSAGE_TYPES, PAGE);
    }

    @Test
    void searchesOnlyUserAndAssistantMessages() {
        assertThat(ChatService.SEARCHABLE_MESSAGE_TYPES).containsExactlyInAnyOrder(MessageType.USER, MessageType.ASSISTANT);
    }

    @Test
    void searchResultsCarryTheirMessagesAndTheirMatch() {
        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        ChatMessage chatMessage = message("set up the UniFi switch");

        when(chatRepository.searchUngroupedByUserId(USER_ID, "%unifi%", ChatService.SEARCHABLE_MESSAGE_TYPES, PAGE))
                .thenReturn(new PageImpl<>(List.of(chat), PAGE, 1));
        when(chatMessageRepository.findByChatId(CHAT_ID)).thenReturn(List.of(chatMessage));
        when(chatAttachmentService.forChat(CHAT_ID)).thenReturn(List.of());
        when(generatedImageService.forChat(CHAT_ID)).thenReturn(List.of());

        Chat result = chatService.searchUngroupedByUserId(USER_ID, " unifi ", PAGE).getContent().getFirst();

        assertThat(result.getChatMessages()).containsExactly(chatMessage);
        assertThat(result.getMatch()).isNotNull();
        assertThat(result.getMatch().chatMessageId()).isEqualTo(chatMessage.getId());
        assertThat(result.getMatch().snippet()).isEqualTo("set up the UniFi switch");
    }

    /**
     * Only the chat's name matched, so there is no message to point at.
     */
    @Test
    void aChatMatchedOnlyByNameHasNoMatch() {
        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        chat.setName("UniFi notes");

        when(chatRepository.searchByUserId(USER_ID, "%unifi%", ChatService.SEARCHABLE_MESSAGE_TYPES, PAGE))
                .thenReturn(new PageImpl<>(List.of(chat), PAGE, 1));
        when(chatMessageRepository.findByChatId(CHAT_ID)).thenReturn(List.of(message("something else")));
        when(chatAttachmentService.forChat(CHAT_ID)).thenReturn(List.of());
        when(generatedImageService.forChat(CHAT_ID)).thenReturn(List.of());

        Chat result = chatService.searchByUserId(USER_ID, "unifi", PAGE).getContent().getFirst();

        assertThat(result.getChatMessages()).hasSize(1);
        assertThat(result.getMatch()).isNull();
    }

    private static ChatMessage message(String text) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setId(UUID.randomUUID());
        chatMessage.setChatId(CHAT_ID);
        chatMessage.setMessageType(MessageType.ASSISTANT);
        chatMessage.setMessage(text);
        chatMessage.setTimestamp(ZonedDateTime.parse("2026-10-24T09:14:40-06:00"));

        return chatMessage;
    }
}
