package com.solesonic.service.ollama;

import com.solesonic.model.chat.history.Chat;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.repository.ollama.ChatMessageRepository;
import com.solesonic.repository.ollama.ChatRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.image.GeneratedImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the ownership rules of {@link ChatService}: the caller's identity comes only from
 * {@link UserRequestContext}, which is resolved from the JWT subject, never from a request
 * parameter — so a chat owned by someone else must be indistinguishable from one that does not
 * exist, on every one of rename, move, and delete.
 * <p>
 * Also pins the arrangement rules for a move, and the cascade a delete has to perform by hand
 * because no foreign key performs it.
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHAT_GROUP_ID = UUID.randomUUID();

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
    private UserRequestContext userRequestContext;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(
                chatRepository,
                chatMessageRepository,
                chatAttachmentService,
                generatedImageService,
                a2aStickyAgentService,
                redisStreamService,
                userRequestContext);
    }

    private Chat chatOwnedByTheCaller() {
        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        chat.setUserId(USER_ID);

        return chat;
    }

    private Chat placedChat(int sortOrder) {
        Chat chat = new Chat();
        chat.setId(UUID.randomUUID());
        chat.setUserId(USER_ID);
        chat.setSortOrder(sortOrder);

        return chat;
    }

    private List<Chat> savedChats() {
        ArgumentCaptor<List<Chat>> savedCaptor = ArgumentCaptor.captor();
        verify(chatRepository).saveAll(savedCaptor.capture());

        return savedCaptor.getValue();
    }

    /**
     * The filtered page is indistinguishable in shape from the unfiltered one — same hydration,
     * same ordering — so a client can switch the filter on without handling a second shape.
     */
    @Test
    void pagesOnlyTheChatsThatAreNotFiledUnderAGroup() {
        Pageable pageable = PageRequest.of(0, 20);
        Chat chat = chatOwnedByTheCaller();

        when(chatRepository.findUngroupedByUserId(USER_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(chat), pageable, 1));
        when(chatMessageRepository.findByChatId(CHAT_ID)).thenReturn(List.of());
        when(chatAttachmentService.forChat(CHAT_ID)).thenReturn(List.of());
        when(generatedImageService.forChat(CHAT_ID)).thenReturn(List.of());

        Page<Chat> chats = chatService.getUngroupedByUserId(USER_ID, pageable);

        assertThat(chats.getContent()).extracting(Chat::getId).containsExactly(CHAT_ID);
        assertThat(chats.getContent().getFirst().getChatMessages()).isEmpty();

        // The unfiltered query must not be the one that ran, or the filter would be silently ignored.
        verify(chatRepository, never()).findByUserId(any(), any());
    }

    @Test
    void renamesAChatOwnedByTheCaller() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chatOwnedByTheCaller()));
        when(chatRepository.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Chat renamed = chatService.rename(CHAT_ID, "Trip planning");

        assertThat(renamed.getName()).isEqualTo("Trip planning");
        verify(chatRepository).save(renamed);
    }

    @Test
    void trimsTheName() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chatOwnedByTheCaller()));
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

    @Test
    void placesTheFirstMovedChatAtTheHeadOfAnEmptyArrangement() {
        Chat chat = chatOwnedByTheCaller();

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));
        when(chatRepository.findPlacedByUserId(USER_ID)).thenReturn(List.of());

        Chat moved = chatService.reorder(CHAT_ID, 0);

        assertThat(moved.getSortOrder()).isZero();
        assertThat(savedChats()).containsExactly(chat);
    }

    /**
     * Moving into the middle renumbers the list densely from zero, and writes only the rows whose
     * number actually changed — the chats above the insertion point keep theirs.
     */
    @Test
    void renumbersOnlyTheChatsAMoveDisturbs() {
        Chat first = placedChat(0);
        Chat second = placedChat(1);
        Chat third = placedChat(2);
        Chat chat = chatOwnedByTheCaller();

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));
        when(chatRepository.findPlacedByUserId(USER_ID))
                .thenReturn(new ArrayList<>(List.of(first, second, third)));

        chatService.reorder(CHAT_ID, 1);

        assertThat(first.getSortOrder()).isZero();
        assertThat(chat.getSortOrder()).isEqualTo(1);
        assertThat(second.getSortOrder()).isEqualTo(2);
        assertThat(third.getSortOrder()).isEqualTo(3);

        // first kept position 0, so it is not rewritten.
        assertThat(savedChats()).containsExactly(chat, second, third);
    }

    /**
     * A drag into the timestamp-ordered part of the list means "last". Rejecting it would only make
     * that gesture unusable.
     */
    @Test
    void appendsAPositionPastTheEndOfTheArrangement() {
        Chat first = placedChat(0);
        Chat chat = chatOwnedByTheCaller();

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));
        when(chatRepository.findPlacedByUserId(USER_ID)).thenReturn(new ArrayList<>(List.of(first)));

        Chat moved = chatService.reorder(CHAT_ID, 99);

        assertThat(moved.getSortOrder()).isEqualTo(1);
    }

    /**
     * A negative index describes no arrangement, and is refused before anything is read — the same
     * shape as a blank rename.
     */
    @Test
    void rejectsANegativePosition() {
        assertThatThrownBy(() -> chatService.reorder(CHAT_ID, -1))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        verify(chatRepository, never()).findByIdAndUserId(any(), any());
        verify(chatRepository, never()).saveAll(anyList());
    }

    /**
     * A null position unplaces the conversation — it returns to timestamp ordering — and the gap it
     * leaves is closed so the remaining arrangement stays dense.
     */
    @Test
    void unplacesAChatAndClosesTheGap() {
        Chat first = placedChat(0);
        Chat third = placedChat(2);
        Chat chat = chatOwnedByTheCaller();
        chat.setSortOrder(1);

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));
        when(chatRepository.findPlacedByUserId(USER_ID))
                .thenReturn(new ArrayList<>(List.of(first, chat, third)));

        Chat moved = chatService.reorder(CHAT_ID, null);

        assertThat(moved.getSortOrder()).isNull();
        assertThat(first.getSortOrder()).isZero();
        assertThat(third.getSortOrder()).isEqualTo(1);
        assertThat(savedChats()).containsExactly(chat, third);
    }

    @Test
    void reportsAChatNotOwnedByTheCallerAsNotFoundOnAMove() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.reorder(CHAT_ID, 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        verify(chatRepository, never()).saveAll(anyList());
    }

    /**
     * The group list is ordered off its own column, so a move inside a group must not touch the
     * position the same conversation holds in the sidebar.
     */
    @Test
    void movesWithinAGroupWithoutDisturbingTheWholeListOrdering() {
        Chat chat = chatOwnedByTheCaller();
        chat.setChatGroupId(CHAT_GROUP_ID);
        chat.setSortOrder(4);

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));
        when(chatRepository.findPlacedByUserIdAndChatGroupId(USER_ID, CHAT_GROUP_ID)).thenReturn(List.of());

        Chat moved = chatService.reorderWithinGroup(CHAT_GROUP_ID, CHAT_ID, 0);

        assertThat(moved.getGroupSortOrder()).isZero();
        assertThat(moved.getSortOrder()).isEqualTo(4);
    }

    @Test
    void refusesToPositionAChatInAGroupItIsNotIn() {
        Chat chat = chatOwnedByTheCaller();
        chat.setChatGroupId(UUID.randomUUID());

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> chatService.reorderWithinGroup(CHAT_GROUP_ID, CHAT_ID, 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        verify(chatRepository, never()).saveAll(anyList());
    }

    /**
     * Nothing in the database cascades a chat delete, so every child table has to be cleared here —
     * otherwise the transcript and the image bytes survive the conversation.
     */
    @Test
    void deletesAChatWithEverythingStoredUnderIt() {
        Chat chat = chatOwnedByTheCaller();

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));
        when(a2aStickyAgentService.deactivate(CHAT_ID)).thenReturn(Mono.empty());
        when(a2aStickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());
        when(redisStreamService.deleteStream(CHAT_ID, USER_ID)).thenReturn(Mono.just(true));

        chatService.delete(CHAT_ID);

        verify(chatAttachmentService).deleteForChat(CHAT_ID);
        verify(generatedImageService).deleteForChat(CHAT_ID);
        verify(chatMessageRepository).deleteByChatId(CHAT_ID);
        verify(chatRepository).delete(chat);
        verify(redisStreamService).deleteStream(CHAT_ID, USER_ID);
    }

    @Test
    void reportsAChatNotOwnedByTheCallerAsNotFoundOnADelete() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.delete(CHAT_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        verify(chatMessageRepository, never()).deleteByChatId(any());
        verify(chatRepository, never()).delete(any(Chat.class));
    }
}
