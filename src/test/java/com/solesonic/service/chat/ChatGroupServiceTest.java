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

    @Test
    void ungroupsAChat() {
        Chat chat = chat(CHAT_GROUP_ID);

        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(chatGroup()));
        when(chatRepository.findByIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(chat));

        chatGroupService.removeChat(CHAT_GROUP_ID, CHAT_ID);

        assertThat(chat.getChatGroupId()).isNull();
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
