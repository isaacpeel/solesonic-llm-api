package com.solesonic.api.chat;

import com.solesonic.service.chat.ChatMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatMessageControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ChatMessageService chatMessageService;

    @InjectMocks
    private ChatMessageController chatMessageController;

    private UUID chatId;
    private UUID messageId;

    @BeforeEach
    void setUp() {
        chatId = UUID.randomUUID();
        messageId = UUID.randomUUID();

        mockMvc = MockMvcBuilders.standaloneSetup(chatMessageController).build();
    }

    @Test
    void deletesAChatMessage() throws Exception {
        mockMvc.perform(delete("/chats/{chatId}/messages/{messageId}", chatId, messageId))
                .andExpect(status().isNoContent());

        verify(chatMessageService).delete(chatId, messageId);
    }

    @Test
    void propagatesNotFoundOnDeletingAMessageFromAChatTheCallerDoesNotOwn() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(chatMessageService).delete(chatId, messageId);

        mockMvc.perform(delete("/chats/{chatId}/messages/{messageId}", chatId, messageId))
                .andExpect(status().isNotFound());
    }
}
