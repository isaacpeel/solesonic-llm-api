package com.solesonic.service.chat;

import com.solesonic.model.chat.group.ChatGroup;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.repository.chat.ChatGroupRepository;
import com.solesonic.repository.ollama.ChatRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.ollama.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the ownership rules of {@link ChatGroupService}: every read and every write is scoped to
 * {@link UserRequestContext#getUserId()}, which comes from the JWT subject rather than from a path
 * segment, so a group or a chat belonging to another user must be indistinguishable from one that
 * does not exist — and must never become a member of a group the caller does own.
 */
@ExtendWith(MockitoExtension.class)
class ChatGroupServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHAT_GROUP_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();

    @Mock
    private ChatGroupRepository chatGroupRepository;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private ChatService chatService;

    @Mock
    private UserRequestContext userRequestContext;

    private ChatGroupService chatGroupService;

    @BeforeEach
    void setUp() {
        chatGroupService = new ChatGroupService(
                chatGroupRepository, chatRepository, chatService, userRequestContext);
    }

    private ChatGroup chatGroup() {
        ChatGroup chatGroup = new ChatGroup();
        chatGroup.setId(CHAT_GROUP_ID);
        chatGroup.setUserId(USER_ID);
        chatGroup.setName("Work");

        return chatGroup;
    }

    /**
     * The body of an update: only the two fields a client may write are populated, the shape
     * Jackson leaves behind for a request that cannot carry the other three.
     */
    private ChatGroup update(String name, Integer sortOrder) {
        ChatGroup chatGroup = new ChatGroup();
        chatGroup.setName(name);
        chatGroup.setSortOrder(sortOrder);

        return chatGroup;
    }

    private Chat chat(UUID chatGroupId) {
        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        chat.setUserId(USER_ID);
        chat.setChatGroupId(chatGroupId);

        return chat;
    }

    @Test
    void createsAGroupOwnedByTheCaller() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.save(any(ChatGroup.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatGroup created = chatGroupService.create("  Work  ");

        assertThat(created.getUserId()).isEqualTo(USER_ID);
        assertThat(created.getName()).isEqualTo("Work");
        assertThat(created.getTimestamp()).isNotNull();
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> chatGroupService.create("   "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        verify(chatGroupRepository, never()).save(any(ChatGroup.class));
    }

    @Test
    void rejectsANameOverTheLengthLimit() {
        String tooLong = "x".repeat(256);

        assertThatThrownBy(() -> chatGroupService.create(tooLong))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        verify(chatGroupRepository, never()).save(any(ChatGroup.class));
    }

    @Test
    void updatesAGroupTheCallerOwns() {
        ChatGroup chatGroup = chatGroup();

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup));
        when(chatGroupRepository.save(any(ChatGroup.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatGroup updated = chatGroupService.update(CHAT_GROUP_ID, update("  Personal  ", 3));

        assertThat(updated.getName()).isEqualTo("Personal");
        assertThat(updated.getSortOrder()).isEqualTo(3);
    }

    /**
     * The update writes onto the group that was read for the caller, never onto the body. A body
     * whose read-only fields were somehow populated cannot reassign the group to another user or
     * rewrite the id the path resolved.
     */
    @Test
    void updatesTheOwnedGroupRatherThanTheSuppliedOne() {
        ChatGroup chatGroup = chatGroup();

        ChatGroup body = update("Personal", 1);
        body.setId(UUID.randomUUID());
        body.setUserId(UUID.randomUUID());

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup));
        when(chatGroupRepository.save(any(ChatGroup.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatGroup updated = chatGroupService.update(CHAT_GROUP_ID, body);

        assertThat(updated).isSameAs(chatGroup);
        assertThat(updated.getId()).isEqualTo(CHAT_GROUP_ID);
        assertThat(updated.getUserId()).isEqualTo(USER_ID);
    }

    /**
     * A full update, not a patch: a body with no sort order unplaces the group and returns it to
     * name ordering rather than leaving the rank it carried.
     */
    @Test
    void unplacesAGroupWhoseUpdateCarriesNoSortOrder() {
        ChatGroup chatGroup = chatGroup();
        chatGroup.setSortOrder(4);

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup));
        when(chatGroupRepository.save(any(ChatGroup.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatGroup updated = chatGroupService.update(CHAT_GROUP_ID, update("Work", null));

        assertThat(updated.getSortOrder()).isNull();
    }

    /**
     * One group is written, and only one: the arrangement is stated a group at a time, so nothing
     * here renumbers the sections around it the way a chat move renumbers the list it lands in.
     */
    @Test
    void writesOnlyTheUpdatedGroup() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup()));
        when(chatGroupRepository.save(any(ChatGroup.class))).thenAnswer(invocation -> invocation.getArgument(0));

        chatGroupService.update(CHAT_GROUP_ID, update("Work", 0));

        verify(chatGroupRepository).save(any(ChatGroup.class));
        verify(chatGroupRepository, never()).findByUserId(any(UUID.class));
        verify(chatGroupRepository, never()).saveAll(anyList());
    }

    @Test
    void rejectsABlankNameOnUpdate() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup()));

        assertThatThrownBy(() -> chatGroupService.update(CHAT_GROUP_ID, update("   ", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        verify(chatGroupRepository, never()).save(any(ChatGroup.class));
    }

    @Test
    void rejectsANameOverTheLengthLimitOnUpdate() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup()));

        assertThatThrownBy(() -> chatGroupService.update(CHAT_GROUP_ID, update("x".repeat(256), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        verify(chatGroupRepository, never()).save(any(ChatGroup.class));
    }

    /**
     * A negative rank is a client bug: there is no arrangement it could describe, and it is refused
     * before anything is written — the same rule a chat's position follows.
     */
    @Test
    void rejectsANegativeSortOrder() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup()));

        assertThatThrownBy(() -> chatGroupService.update(CHAT_GROUP_ID, update("Work", -1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        verify(chatGroupRepository, never()).save(any(ChatGroup.class));
    }

    @Test
    void doesNotUpdateAGroupTheCallerDoesNotOwn() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatGroupService.update(CHAT_GROUP_ID, update("Personal", 1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        verify(chatGroupRepository, never()).save(any(ChatGroup.class));
    }

    /**
     * Ownership is resolved before the body is validated, so a bad name sent for someone else's
     * group is answered as 404 rather than 400 — the endpoint must not tell the caller the
     * difference between "your body was wrong" and "that group is not yours".
     */
    @Test
    void reportsAGroupTheCallerDoesNotOwnAsNotFoundEvenWhenTheBodyIsInvalid() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatGroupService.update(CHAT_GROUP_ID, update("   ", -1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    /**
     * A group is a section, not a container of conversations: deleting one ungroups its chats and
     * deletes nothing else. The positions inside it go with it, since a place in a group that no
     * longer exists describes nothing.
     */
    @Test
    void deletesAGroupAndUngroupsItsChats() {
        ChatGroup chatGroup = chatGroup();

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup));
        when(chatRepository.clearChatGroup(USER_ID, CHAT_GROUP_ID)).thenReturn(2);

        chatGroupService.delete(CHAT_GROUP_ID);

        InOrder inOrder = inOrder(chatRepository, chatGroupRepository);
        inOrder.verify(chatRepository).clearChatGroup(USER_ID, CHAT_GROUP_ID);
        inOrder.verify(chatGroupRepository).delete(chatGroup);
    }

    @Test
    void doesNotDeleteAGroupTheCallerDoesNotOwn() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatGroupService.delete(CHAT_GROUP_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        verify(chatRepository, never()).clearChatGroup(any(), any());
        verify(chatGroupRepository, never()).delete(any(ChatGroup.class));
    }

    @Test
    void listsOnlyTheCallersGroups() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByUserId(USER_ID)).thenReturn(List.of(chatGroup()));

        assertThat(chatGroupService.get()).extracting(ChatGroup::getId).containsExactly(CHAT_GROUP_ID);
    }

    @Test
    void reportsAGroupNotOwnedByTheCallerAsNotFound() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatGroupService.get(CHAT_GROUP_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void pagesTheChatsOfAGroupTheCallerOwns() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Chat> chats = new PageImpl<>(List.of(chat(CHAT_GROUP_ID)));

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup()));
        when(chatService.getByUserIdAndChatGroupId(USER_ID, CHAT_GROUP_ID, pageable)).thenReturn(chats);

        assertThat(chatGroupService.chats(CHAT_GROUP_ID, pageable)).isSameAs(chats);
    }

    @Test
    void doesNotReadTheChatsOfAGroupTheCallerDoesNotOwn() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatGroupService.chats(CHAT_GROUP_ID, PageRequest.of(0, 20)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        verify(chatService, never()).getByUserIdAndChatGroupId(any(), any(), any());
    }

    @Test
    void filesAChatUnderAGroup() {
        Chat chat = chat(null);

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup()));
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));

        chatGroupService.addChat(CHAT_GROUP_ID, CHAT_ID);

        assertThat(chat.getChatGroupId()).isEqualTo(CHAT_GROUP_ID);
        verify(chatRepository).save(chat);
    }

    /**
     * The group is the caller's, the chat is not. Nothing may be written: a chat another user owns
     * must not be filable into a group this one owns.
     */
    @Test
    void doesNotFileAChatTheCallerDoesNotOwn() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup()));
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatGroupService.addChat(CHAT_GROUP_ID, CHAT_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        verify(chatRepository, never()).save(any(Chat.class));
    }

    /**
     * A position describes a place in one group's list, so it cannot follow the conversation into
     * another group.
     */
    @Test
    void clearsThePositionWhenAChatChangesGroup() {
        Chat chat = chat(UUID.randomUUID());
        chat.setGroupSortOrder(3);

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup()));
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));

        chatGroupService.addChat(CHAT_GROUP_ID, CHAT_ID);

        assertThat(chat.getChatGroupId()).isEqualTo(CHAT_GROUP_ID);
        assertThat(chat.getGroupSortOrder()).isNull();
    }

    /**
     * Re-filing a chat into the group it is already in is idempotent, so it must not quietly throw
     * away the position the user arranged.
     */
    @Test
    void keepsThePositionWhenAChatIsFiledIntoTheGroupItIsAlreadyIn() {
        Chat chat = chat(CHAT_GROUP_ID);
        chat.setGroupSortOrder(3);

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup()));
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));

        chatGroupService.addChat(CHAT_GROUP_ID, CHAT_ID);

        assertThat(chat.getGroupSortOrder()).isEqualTo(3);
    }

    @Test
    void movesAChatWithinAGroupTheCallerOwns() {
        Chat chat = chat(CHAT_GROUP_ID);

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup()));
        when(chatService.reorderWithinGroup(CHAT_GROUP_ID, CHAT_ID, 2)).thenReturn(chat);

        assertThat(chatGroupService.reorderChat(CHAT_GROUP_ID, CHAT_ID, 2)).isSameAs(chat);
    }

    @Test
    void doesNotMoveAChatInAGroupTheCallerDoesNotOwn() {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatGroupService.reorderChat(CHAT_GROUP_ID, CHAT_ID, 2))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        verify(chatService, never()).reorderWithinGroup(any(), any(), any());
    }

    @Test
    void ungroupsAChat() {
        Chat chat = chat(CHAT_GROUP_ID);
        chat.setGroupSortOrder(3);

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup()));
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));

        chatGroupService.removeChat(CHAT_GROUP_ID, CHAT_ID);

        assertThat(chat.getChatGroupId()).isNull();
        assertThat(chat.getGroupSortOrder()).isNull();
        verify(chatRepository).save(chat);
    }

    /**
     * Removing a chat from a group it is not in is a 404 rather than a silent success — the
     * client's picture of where the conversation lives is wrong, and reporting the removal as done
     * would leave it wrong.
     */
    @Test
    void refusesToRemoveAChatFromAGroupItIsNotIn() {
        Chat chat = chat(UUID.randomUUID());

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup()));
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> chatGroupService.removeChat(CHAT_GROUP_ID, CHAT_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        assertThat(chat.getChatGroupId()).isNotNull();
        verify(chatRepository, never()).save(any(Chat.class));
    }
}
