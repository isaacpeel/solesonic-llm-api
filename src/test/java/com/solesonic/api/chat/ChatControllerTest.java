package com.solesonic.api.chat;

import com.solesonic.model.chat.history.Chat;
import com.solesonic.service.ollama.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ChatService chatService;

    @InjectMocks
    private ChatController chatController;

    private UUID chatId;

    @BeforeEach
    void setUp() {
        chatId = UUID.randomUUID();
        mockMvc = MockMvcBuilders.standaloneSetup(chatController).build();
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
}
