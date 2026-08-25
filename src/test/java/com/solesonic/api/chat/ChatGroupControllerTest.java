package com.solesonic.api.chat;

import com.solesonic.model.chat.group.ChatGroup;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.service.chat.ChatGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatGroupControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ChatGroupService chatGroupService;

    @InjectMocks
    private ChatGroupController chatGroupController;

    private UUID chatGroupId;
    private UUID chatId;

    @BeforeEach
    void setUp() {
        chatGroupId = UUID.randomUUID();
        chatId = UUID.randomUUID();
        mockMvc = MockMvcBuilders.standaloneSetup(chatGroupController)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    private ChatGroup chatGroup() {
        ChatGroup chatGroup = new ChatGroup();
        chatGroup.setId(chatGroupId);
        chatGroup.setName("Work");

        return chatGroup;
    }

    @Test
    void createsAGroup() throws Exception {
        when(chatGroupService.create("Work")).thenReturn(chatGroup());

        mockMvc.perform(post("/chatgroups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Work\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/chatgroups/" + chatGroupId))
                .andExpect(jsonPath("$.id").value(chatGroupId.toString()))
                .andExpect(jsonPath("$.name").value("Work"));
    }

    @Test
    void rejectsABlankName() throws Exception {
        when(chatGroupService.create(" "))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST));

        mockMvc.perform(post("/chatgroups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatesAGroup() throws Exception {
        ChatGroup updated = chatGroup();
        updated.setName("Personal");
        updated.setSortOrder(2);

        when(chatGroupService.update(eq(chatGroupId), any(ChatGroup.class))).thenReturn(updated);

        mockMvc.perform(put("/chatgroups/{chatGroupId}", chatGroupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Personal\",\"sortOrder\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(chatGroupId.toString()))
                .andExpect(jsonPath("$.name").value("Personal"))
                .andExpect(jsonPath("$.sortOrder").value(2));

        // Both writable fields reach the service untouched; trimming, the length rule and the
        // negative-rank rule are its job.
        ArgumentCaptor<ChatGroup> chatGroupCaptor = ArgumentCaptor.forClass(ChatGroup.class);
        verify(chatGroupService).update(eq(chatGroupId), chatGroupCaptor.capture());

        assertThat(chatGroupCaptor.getValue().getName()).isEqualTo("Personal");
        assertThat(chatGroupCaptor.getValue().getSortOrder()).isEqualTo(2);
    }

    /**
     * The three read-only fields are read-only in both directions: a body that carries an id, a
     * userId or a timestamp is deserialized without them, so no update can be talked into writing
     * one. Ownership comes from the JWT subject and the id from the path.
     */
    @Test
    void ignoresTheReadOnlyFieldsOfAnUpdateBody() throws Exception {
        when(chatGroupService.update(eq(chatGroupId), any(ChatGroup.class))).thenReturn(chatGroup());

        mockMvc.perform(put("/chatgroups/{chatGroupId}", chatGroupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + UUID.randomUUID() + "\","
                                + "\"userId\":\"" + UUID.randomUUID() + "\","
                                + "\"timestamp\":\"2026-08-23T16:40:14Z\","
                                + "\"name\":\"Personal\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ChatGroup> chatGroupCaptor = ArgumentCaptor.forClass(ChatGroup.class);
        verify(chatGroupService).update(eq(chatGroupId), chatGroupCaptor.capture());

        assertThat(chatGroupCaptor.getValue().getId()).isNull();
        assertThat(chatGroupCaptor.getValue().getUserId()).isNull();
        assertThat(chatGroupCaptor.getValue().getTimestamp()).isNull();
    }

    /**
     * A full update, not a patch: a body with no sortOrder unplaces the group rather than leaving
     * the rank it carried.
     */
    @Test
    void unplacesAGroupWhoseUpdateOmitsTheSortOrder() throws Exception {
        when(chatGroupService.update(eq(chatGroupId), any(ChatGroup.class))).thenReturn(chatGroup());

        mockMvc.perform(put("/chatgroups/{chatGroupId}", chatGroupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Personal\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ChatGroup> chatGroupCaptor = ArgumentCaptor.forClass(ChatGroup.class);
        verify(chatGroupService).update(eq(chatGroupId), chatGroupCaptor.capture());

        assertThat(chatGroupCaptor.getValue().getSortOrder()).isNull();
    }

    @Test
    void propagatesNotFoundForUpdatingAGroupTheCallerDoesNotOwn() throws Exception {
        when(chatGroupService.update(eq(chatGroupId), any(ChatGroup.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mockMvc.perform(put("/chatgroups/{chatGroupId}", chatGroupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Personal\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesAGroup() throws Exception {
        mockMvc.perform(delete("/chatgroups/{chatGroupId}", chatGroupId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(chatGroupService).delete(chatGroupId);
    }

    /**
     * The group is deleted, not one conversation in it. The two DELETEs sit on adjacent paths and
     * are the pair most likely to be confused for each other.
     */
    @Test
    void deletingAGroupDoesNotRemoveASingleChat() throws Exception {
        mockMvc.perform(delete("/chatgroups/{chatGroupId}", chatGroupId))
                .andExpect(status().isNoContent());

        verify(chatGroupService, never()).removeChat(any(), any());
    }

    @Test
    void propagatesNotFoundForDeletingAGroupTheCallerDoesNotOwn() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(chatGroupService).delete(chatGroupId);

        mockMvc.perform(delete("/chatgroups/{chatGroupId}", chatGroupId))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsGroups() throws Exception {
        when(chatGroupService.get()).thenReturn(List.of(chatGroup()));

        mockMvc.perform(get("/chatgroups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(chatGroupId.toString()))
                .andExpect(jsonPath("$[0].name").value("Work"));
    }

    @Test
    void getsOneGroup() throws Exception {
        when(chatGroupService.get(chatGroupId)).thenReturn(chatGroup());

        mockMvc.perform(get("/chatgroups/{chatGroupId}", chatGroupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Work"));
    }

    @Test
    void pagesTheChatsOfAGroup() throws Exception {
        Chat chat = new Chat();
        chat.setId(chatId);
        chat.setChatGroupId(chatGroupId);

        when(chatGroupService.chats(eq(chatGroupId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(chat), PageRequest.of(1, 5), 8));

        mockMvc.perform(get("/chatgroups/{chatGroupId}/chats", chatGroupId)
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(chatId.toString()))
                .andExpect(jsonPath("$.content[0].chatGroupId").value(chatGroupId.toString()))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(5));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(chatGroupService).chats(eq(chatGroupId), pageableCaptor.capture());

        // Only the window reaches the service: the ordering belongs to the repository query.
        assertThat(pageableCaptor.getValue().getSort().isSorted()).isFalse();
    }

    @Test
    void addsAChatToAGroup() throws Exception {
        mockMvc.perform(put("/chatgroups/{chatGroupId}/chats/{chatId}", chatGroupId, chatId))
                .andExpect(status().isNoContent());

        verify(chatGroupService).addChat(chatGroupId, chatId);
    }

    @Test
    void removesAChatFromAGroup() throws Exception {
        mockMvc.perform(delete("/chatgroups/{chatGroupId}/chats/{chatId}", chatGroupId, chatId))
                .andExpect(status().isNoContent());

        verify(chatGroupService).removeChat(chatGroupId, chatId);
    }

    @Test
    void movesAChatWithinAGroup() throws Exception {
        Chat chat = new Chat();
        chat.setId(chatId);
        chat.setChatGroupId(chatGroupId);
        chat.setGroupSortOrder(1);

        when(chatGroupService.reorderChat(chatGroupId, chatId, 1)).thenReturn(chat);

        mockMvc.perform(put("/chatgroups/{chatGroupId}/chats/{chatId}/order", chatGroupId, chatId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupSortOrder").value(1));
    }

    @Test
    void propagatesNotFoundForAGroupTheCallerDoesNotOwn() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(chatGroupService).addChat(chatGroupId, chatId);

        mockMvc.perform(put("/chatgroups/{chatGroupId}/chats/{chatId}", chatGroupId, chatId))
                .andExpect(status().isNotFound());
    }
}
