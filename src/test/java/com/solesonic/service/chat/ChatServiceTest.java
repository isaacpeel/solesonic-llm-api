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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Mock
    private com.solesonic.repository.session.SharedSessionRepository sharedSessionRepository;

    @Mock
    private com.solesonic.repository.session.SessionParticipantRepository sessionParticipantRepository;

    @Mock
    private ConversationSessionCleanup conversationSessionCleanup;

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
                userRequestContext,
                sharedSessionRepository,
                sessionParticipantRepository,
                conversationSessionCleanup);
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
        verify(conversationSessionCleanup).forget(CHAT_ID);
    }

    @Test
    void deletingAChatTheCallerDoesNotOwnForgetsNothing() {
        when(chatRepository.lockByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.delete(CHAT_ID)).isInstanceOf(ResponseStatusException.class);

        verify(conversationSessionCleanup, never()).forget(any());
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

        verify(chatRepository, never()).searchByUserId(any(), any(), any(), any(), any());
    }

    @Test
    void searchRejectsAQueryLongerThanTwoHundredCharacters() {
        assertThatThrownBy(() -> chatService.searchUngroupedByUserId(USER_ID, "a".repeat(201), PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(BAD_REQUEST);

        verify(chatRepository, never()).searchUngroupedByUserId(any(), any(), any(), any(), any());
    }

    @Test
    void searchAcceptsTwoAndTwoHundredCharacters() {
        when(chatRepository.searchByUserId(eq(USER_ID), any(), any(), any(), eq(PAGE))).thenReturn(Page.empty(PAGE));

        assertThat(chatService.searchByUserId(USER_ID, "ab", PAGE)).isEmpty();
        assertThat(chatService.searchByUserId(USER_ID, "a".repeat(200), PAGE)).isEmpty();
    }

    @Test
    void searchTrimsLowercasesAndEscapesTheLikePattern() {
        when(chatRepository.searchByUserId(eq(USER_ID), any(), any(), any(), eq(PAGE))).thenReturn(Page.empty(PAGE));

        chatService.searchByUserId(USER_ID, "  50%_OFF\\now  ", PAGE);

        verify(chatRepository).searchByUserId(eq(USER_ID), any(), eq("%50\\%\\_off\\\\now%"), eq(ChatService.SEARCHABLE_MESSAGE_TYPES), eq(PAGE));
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

        when(chatRepository.searchUngroupedByUserId(eq(USER_ID), any(), eq("%unifi%"), eq(ChatService.SEARCHABLE_MESSAGE_TYPES), eq(PAGE)))
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

        when(chatRepository.searchByUserId(eq(USER_ID), any(), eq("%unifi%"), eq(ChatService.SEARCHABLE_MESSAGE_TYPES), eq(PAGE)))
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

    // ---- shared chats

    private Chat sharedChat(com.solesonic.model.chat.history.ChatKind kind) {
        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        chat.setUserId(USER_ID);
        chat.setKind(kind);

        return chat;
    }

    private void stubTransientState() {
        lenient().when(a2aStickyAgentService.deactivate(CHAT_ID)).thenReturn(Mono.empty());
        lenient().when(a2aStickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());
        lenient().when(redisStreamService.deleteStream(CHAT_ID, USER_ID)).thenReturn(Mono.just(true));
        lenient().when(chatModelSelection.clear(CHAT_ID)).thenReturn(Mono.empty());
    }

    /**
     * Everyone's uploads and the images generated in the room are stored under the chat with no foreign
     * key, so deleting the row alone leaves them behind.
     */
    @Test
    void deletingASharedChatDeletesItsAttachmentsDocumentsAndGeneratedImages() {
        Chat chat = sharedChat(com.solesonic.model.chat.history.ChatKind.SHARED);
        when(chatRepository.lockById(CHAT_ID)).thenReturn(Optional.of(chat));
        stubTransientState();

        chatService.deleteSharedChat(CHAT_ID);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(chatAttachmentService, generatedImageService, chatMessageRepository, chatRepository);
        inOrder.verify(chatAttachmentService).deleteForChat(CHAT_ID);
        inOrder.verify(generatedImageService).deleteForChat(CHAT_ID);
        inOrder.verify(chatMessageRepository).deleteByChatId(CHAT_ID);
        inOrder.verify(chatRepository).delete(chat);
        verify(redisStreamService).deleteStream(CHAT_ID, USER_ID);
    }

    /**
     * A shared conversation runs the default route, so it has a model session, which has no foreign key
     * to the chat either.
     */
    @Test
    void deletingASharedChatDropsItsModelSession() {
        when(chatRepository.lockById(CHAT_ID)).thenReturn(Optional.of(sharedChat(com.solesonic.model.chat.history.ChatKind.SHARED)));
        stubTransientState();

        chatService.deleteSharedChat(CHAT_ID);

        verify(conversationSessionCleanup).forget(CHAT_ID);
    }

    @Test
    void deletingASharedChatLeavesNoPersonalMemoryOrInterestCleanupToRun() {
        when(chatRepository.lockById(CHAT_ID)).thenReturn(Optional.of(sharedChat(com.solesonic.model.chat.history.ChatKind.SHARED)));
        stubTransientState();

        chatService.deleteSharedChat(CHAT_ID);

        verifyNoInteractions(userMemoryService, interestUpdateService);
    }

    @Test
    void deleteSharedChatNeverTouchesAPrivateChat() {
        when(chatRepository.lockById(CHAT_ID)).thenReturn(Optional.of(sharedChat(com.solesonic.model.chat.history.ChatKind.CONVERSATION)));

        chatService.deleteSharedChat(CHAT_ID);

        verifyNoInteractions(chatAttachmentService, generatedImageService, conversationSessionCleanup);
        verify(chatRepository, never()).delete(any());
    }

    // ---- shared chats in history, rename, delete, order and model access ----

    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    private Chat shared(UUID ownerId) {
        Chat chat = sharedChat(com.solesonic.model.chat.history.ChatKind.SHARED);
        chat.setUserId(ownerId);
        return chat;
    }

    @Test
    void theHistoryListAsksForTheCallersSeatedSharedChatsAndFlagsThemWithTheirPeople() {
        Chat sharedChat = shared(OTHER_ID);
        com.solesonic.model.session.SharedSession session = new com.solesonic.model.session.SharedSession();
        session.setId(SESSION_ID);
        session.setChatId(CHAT_ID);
        com.solesonic.model.session.SessionParticipant seated = new com.solesonic.model.session.SessionParticipant();
        seated.setSessionId(SESSION_ID);
        seated.setUserId(OTHER_ID);
        seated.setName("Alex");

        when(sharedSessionRepository.findVisibleConversationChatIds(eq(USER_ID), any())).thenReturn(List.of(CHAT_ID));
        when(chatRepository.findByUserId(eq(USER_ID), eq(List.of(CHAT_ID)), eq(PAGE)))
                .thenReturn(new PageImpl<>(List.of(sharedChat), PAGE, 1));
        when(sharedSessionRepository.findAllByChatIdIn(List.of(CHAT_ID))).thenReturn(List.of(session));
        when(sessionParticipantRepository.findBySessionIdInAndLeftAtIsNullOrderBySeatAsc(List.of(SESSION_ID))).thenReturn(List.of(seated));
        when(chatMessageRepository.findByChatId(CHAT_ID)).thenReturn(List.of());
        when(chatAttachmentService.forChat(CHAT_ID)).thenReturn(List.of());
        when(generatedImageService.forChat(CHAT_ID)).thenReturn(List.of());

        Chat listed = chatService.getByUserId(USER_ID, PAGE).getContent().getFirst();

        assertThat(listed.getSharedSessionId()).isEqualTo(SESSION_ID);
        assertThat(listed.getOwnerUserId()).isEqualTo(OTHER_ID);
        assertThat(listed.getParticipants()).containsExactly(new com.solesonic.model.chat.history.ChatParticipant(OTHER_ID, "Alex"));
    }

    @Test
    void aPrivateChatKeepsNullSharedFieldsAndNoParticipantsAndCostsNoSessionQueries() {
        Chat privateChat = new Chat();
        privateChat.setId(CHAT_ID);

        when(sharedSessionRepository.findVisibleConversationChatIds(eq(USER_ID), any())).thenReturn(List.of());
        when(chatRepository.findByUserId(eq(USER_ID), eq(List.of(new UUID(0, 0))), eq(PAGE)))
                .thenReturn(new PageImpl<>(List.of(privateChat), PAGE, 1));
        when(chatMessageRepository.findByChatId(CHAT_ID)).thenReturn(List.of());
        when(chatAttachmentService.forChat(CHAT_ID)).thenReturn(List.of());
        when(generatedImageService.forChat(CHAT_ID)).thenReturn(List.of());

        Chat listed = chatService.getByUserId(USER_ID, PAGE).getContent().getFirst();

        assertThat(listed.getSharedSessionId()).isNull();
        assertThat(listed.getOwnerUserId()).isNull();
        assertThat(listed.getParticipants()).isEmpty();
        verify(sharedSessionRepository, never()).findAllByChatIdIn(any());
    }

    @Test
    void theOwnerOfASharedChatCanRenameIt() {
        Chat sharedChat = shared(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());
        when(chatRepository.findVisibleSharedById(CHAT_ID, USER_ID)).thenReturn(Optional.of(sharedChat));
        when(chatRepository.save(sharedChat)).thenReturn(sharedChat);
        when(sharedSessionRepository.findAllByChatIdIn(any())).thenReturn(List.of());

        assertThat(chatService.rename(CHAT_ID, " Trip ").getName()).isEqualTo("Trip");
    }

    @Test
    void aSeatedParticipantWhoDoesNotOwnTheSharedChatCannotRenameIt() {
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());
        when(chatRepository.findVisibleSharedById(CHAT_ID, USER_ID)).thenReturn(Optional.of(shared(OTHER_ID)));

        assertThatThrownBy(() -> chatService.rename(CHAT_ID, "Trip"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(NOT_FOUND);

        verify(chatRepository, never()).save(any());
    }

    @Test
    void orderingASharedChatIsTheOwnersAloneToo() {
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());
        when(chatRepository.findVisibleSharedById(CHAT_ID, USER_ID)).thenReturn(Optional.of(shared(OTHER_ID)));

        assertThatThrownBy(() -> chatService.reorder(CHAT_ID, 0))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(NOT_FOUND);
    }

    @Test
    void deletingASharedChatIsRefusedWithAConflictCodeForTheOwnerAndAParticipant() {
        when(chatRepository.lockByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());
        when(chatRepository.findVisibleSharedById(CHAT_ID, USER_ID)).thenReturn(Optional.of(shared(OTHER_ID)));

        assertThatThrownBy(() -> chatService.delete(CHAT_ID))
                .isInstanceOfSatisfying(com.solesonic.exception.session.SessionException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(com.solesonic.model.session.SessionErrorCode.SHARED_CHAT_USE_LEAVE));

        verify(chatRepository, never()).delete(any());
        verifyNoInteractions(chatAttachmentService, generatedImageService);
    }

    @Test
    void deletingAChatTheCallerIsNotSeatedInIsStillNotFound() {
        when(chatRepository.lockByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());
        when(chatRepository.findVisibleSharedById(CHAT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.delete(CHAT_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(NOT_FOUND);
    }

    @Test
    void modelAccessIsGrantedToTheOwnerOfAPrivateChatAndToASeatedParticipantOnly() {
        Chat privateChat = new Chat();
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(privateChat));

        assertThat(chatService.requireModelAccess(CHAT_ID)).isSameAs(privateChat);

        Chat sharedChat = shared(OTHER_ID);
        UUID sharedId = UUID.randomUUID();
        when(chatRepository.findByIdAndUserId(sharedId, USER_ID)).thenReturn(Optional.empty());
        when(chatRepository.findVisibleSharedById(sharedId, USER_ID)).thenReturn(Optional.of(sharedChat));

        assertThat(chatService.requireModelAccess(sharedId)).isSameAs(sharedChat);

        UUID strangersId = UUID.randomUUID();
        when(chatRepository.findByIdAndUserId(strangersId, USER_ID)).thenReturn(Optional.empty());
        when(chatRepository.findVisibleSharedById(strangersId, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.requireModelAccess(strangersId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(NOT_FOUND);
    }

    private ChatMessage replyWithLiteLlmInternals() {
        ChatMessage reply = message("here it is");
        com.solesonic.model.chat.LiteLlmCallMetadata liteLlm = new com.solesonic.model.chat.LiteLlmCallMetadata(
                "call-1", "gpt", "http://10.0.0.5:8585/v1", 0, 0, 120.0, 3.0);
        reply.setResponseMetadataCalls(List.of(new com.solesonic.model.chat.ModelCallMetadata("qwen", "chatcmpl-1", null, "stop", 10, 20, 30,
                41.5, 1.0, 2.0, 3.0, null, null, null, null, null, null, null, null, liteLlm)));
        return reply;
    }

    @Test
    void theHistoryListHidesTheLiteLlmInternalsOfASharedChatsMessagesButNotOfAPrivateOnes() {
        Chat sharedChat = shared(OTHER_ID);
        UUID privateId = UUID.randomUUID();
        Chat privateChat = new Chat();
        privateChat.setId(privateId);
        ChatMessage inShared = replyWithLiteLlmInternals();
        ChatMessage inPrivate = replyWithLiteLlmInternals();

        when(sharedSessionRepository.findVisibleConversationChatIds(eq(USER_ID), any())).thenReturn(List.of(CHAT_ID));
        when(chatRepository.findByUserId(eq(USER_ID), any(), eq(PAGE)))
                .thenReturn(new PageImpl<>(List.of(sharedChat, privateChat), PAGE, 2));
        when(sharedSessionRepository.findAllByChatIdIn(any())).thenReturn(List.of());
        when(chatMessageRepository.findByChatId(CHAT_ID)).thenReturn(List.of(inShared));
        when(chatMessageRepository.findByChatId(privateId)).thenReturn(List.of(inPrivate));
        when(chatAttachmentService.forChat(any())).thenReturn(List.of());
        when(generatedImageService.forChat(any())).thenReturn(List.of());

        List<Chat> listed = chatService.getByUserId(USER_ID, PAGE).getContent();

        assertThat(listed.get(0).getChatMessages().getFirst().getResponseMetadataCalls().getFirst().liteLlm()).isNull();
        assertThat(listed.get(0).getChatMessages().getFirst().getResponseMetadataCalls().getFirst().totalTokens()).isEqualTo(30);
        assertThat(listed.get(1).getChatMessages().getFirst().getResponseMetadataCalls().getFirst().liteLlm()).isNotNull();
    }

    @Test
    void theHistoryListHidesThemInSearchResultsToo() {
        Chat sharedChat = shared(OTHER_ID);
        ChatMessage inShared = replyWithLiteLlmInternals();

        when(sharedSessionRepository.findVisibleConversationChatIds(eq(USER_ID), any())).thenReturn(List.of(CHAT_ID));
        when(chatRepository.searchByUserId(eq(USER_ID), any(), any(), any(), eq(PAGE)))
                .thenReturn(new PageImpl<>(List.of(sharedChat), PAGE, 1));
        when(sharedSessionRepository.findAllByChatIdIn(any())).thenReturn(List.of());
        when(chatMessageRepository.findByChatId(CHAT_ID)).thenReturn(List.of(inShared));
        when(chatAttachmentService.forChat(any())).thenReturn(List.of());
        when(generatedImageService.forChat(any())).thenReturn(List.of());

        Chat result = chatService.searchByUserId(USER_ID, "here", PAGE).getContent().getFirst();

        assertThat(result.getChatMessages().getFirst().getResponseMetadataCalls().getFirst().liteLlm()).isNull();
    }

    @Test
    void theSharedChatIdListIsCappedToTheMostRecentSeats() {
        when(sharedSessionRepository.findVisibleConversationChatIds(eq(USER_ID), any())).thenReturn(List.of());
        when(chatRepository.findByUserId(eq(USER_ID), any(), eq(PAGE))).thenReturn(Page.empty(PAGE));

        chatService.getByUserId(USER_ID, PAGE);

        verify(sharedSessionRepository).findVisibleConversationChatIds(USER_ID,
                org.springframework.data.domain.Limit.of(ChatService.MAX_SHARED_CHATS_IN_HISTORY));
        assertThat(ChatService.MAX_SHARED_CHATS_IN_HISTORY).isEqualTo(500);
    }
}
