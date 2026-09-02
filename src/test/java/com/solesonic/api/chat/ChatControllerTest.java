package com.solesonic.api.chat;

import com.solesonic.model.chat.history.Chat;
import com.solesonic.service.chat.ChatService;
import com.solesonic.service.security.ResourceOwnershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ChatService chatService;

    @Mock
    private ResourceOwnershipService resourceOwnershipService;

    @InjectMocks
    private ChatController chatController;

    private UUID chatId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        chatId = UUID.randomUUID();
        userId = UUID.randomUUID();

        // Only getUserChats consults this; lenient because most tests here exercise the
        // chatId-keyed endpoints, which never call it.
        lenient().when(resourceOwnershipService.isOwner(eq(userId), any())).thenReturn(true);

        mockMvc = MockMvcBuilders.standaloneSetup(chatController)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    /**
     * The filter is opt-in. Every existing client asks without it and must keep receiving the whole
     * list, grouped conversations included.
     */
    @Test
    void listsEveryChatWhenTheFilterIsNotAskedFor() throws Exception {
        Chat chat = new Chat();
        chat.setId(chatId);

        when(chatService.getByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(chat), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/chats/users/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(chatId.toString()));

        verify(chatService, never()).getUngroupedByUserId(any(), any());
    }

    @Test
    void deniesListingAnotherUsersChats() throws Exception {
        when(resourceOwnershipService.isOwner(eq(userId), any())).thenReturn(false);

        mockMvc.perform(get("/chats/users/{userId}", userId))
                .andExpect(status().isForbidden());

        verify(chatService, never()).getByUserId(any(), any());
        verify(chatService, never()).getUngroupedByUserId(any(), any());
    }

    @Test
    void listsOnlyUngroupedChatsWhenTheFilterIsAskedFor() throws Exception {
        Chat chat = new Chat();
        chat.setId(chatId);

        when(chatService.getUngroupedByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(chat), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/chats/users/{userId}", userId).param("ungrouped", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(chatId.toString()))
                .andExpect(jsonPath("$.content[0].chatGroupId").doesNotExist());

        verify(chatService, never()).getByUserId(any(), any());
    }

    @Test
    void listsEveryChatWhenTheFilterIsAskedForExplicitlyFalse() throws Exception {
        when(chatService.getByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/chats/users/{userId}", userId).param("ungrouped", "false"))
                .andExpect(status().isOk());

        verify(chatService, never()).getUngroupedByUserId(any(), any());
    }

    @Test
    void getsAChat() throws Exception {
        Chat chat = new Chat();
        chat.setId(chatId);

        when(chatService.get(chatId)).thenReturn(chat);

        mockMvc.perform(get("/chats/{chatId}", chatId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(chatId.toString()));
    }

    @Test
    void propagatesNotFoundForAChatTheCallerDoesNotOwnOnGet() throws Exception {
        when(chatService.get(chatId)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mockMvc.perform(get("/chats/{chatId}", chatId))
                .andExpect(status().isNotFound());
    }

    @Test
    void renamesAChat() throws Exception {
        Chat renamed = new Chat();
        renamed.setId(chatId);
        renamed.setName("Trip planning");

        when(chatService.rename(chatId, "Trip planning")).thenReturn(renamed);

        mockMvc.perform(put("/chats/{chatId}/name", chatId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Trip planning\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(chatId.toString()))
                .andExpect(jsonPath("$.name").value("Trip planning"));
    }

    @Test
    void propagatesNotFoundForAChatTheCallerDoesNotOwn() throws Exception {
        when(chatService.rename(chatId, "Trip planning"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mockMvc.perform(put("/chats/{chatId}/name", chatId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Trip planning\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void movesAChat() throws Exception {
        Chat moved = new Chat();
        moved.setId(chatId);
        moved.setSortOrder(2);

        when(chatService.reorder(chatId, 2)).thenReturn(moved);

        mockMvc.perform(put("/chats/{chatId}/order", chatId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sortOrder").value(2));
    }

    /**
     * A null position is the documented way to unplace a conversation, so it has to reach the
     * service as a null rather than being rejected or defaulted on the way in.
     */
    @Test
    void unplacesAChatOnANullPosition() throws Exception {
        Chat moved = new Chat();
        moved.setId(chatId);

        when(chatService.reorder(chatId, null)).thenReturn(moved);

        mockMvc.perform(put("/chats/{chatId}/order", chatId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sortOrder").doesNotExist());

        verify(chatService).reorder(chatId, null);
    }

    @Test
    void deletesAChat() throws Exception {
        mockMvc.perform(delete("/chats/{chatId}", chatId))
                .andExpect(status().isNoContent());

        verify(chatService).delete(chatId);
    }

    @Test
    void propagatesNotFoundOnDeletingAChatTheCallerDoesNotOwn() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(chatService).delete(chatId);

        mockMvc.perform(delete("/chats/{chatId}", chatId))
                .andExpect(status().isNotFound());
    }
}
