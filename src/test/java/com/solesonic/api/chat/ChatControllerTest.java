package com.solesonic.api.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.service.ollama.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.ai.chat.messages.MessageType.ASSISTANT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
public class ChatControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Mock
    private ChatService chatService;

    @InjectMocks
    private ChatController chatController;

    private UUID userId;
    private UUID chatId;
    private Chat chat;
    private ChatMessage chatMessage;
    private ChatRequest chatRequest;
    private SolesonicChatResponse solesonicChatResponse;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        chatId = UUID.randomUUID();

        // Set up Chat
        chat = new Chat();
        chat.setId(chatId);
        chat.setUserId(userId);
        chat.setTimestamp(ZonedDateTime.now());

        // Set up ChatMessage
        chatMessage = new ChatMessage();
        chatMessage.setChatId(chatId);
        chatMessage.setMessageType(ASSISTANT);
        chatMessage.setMessage("Hello, how can I help you?");
        chatMessage.setModel("llama3");

        // Set up ChatRequest
        chatRequest = new ChatRequest("Hello, I need help with something.");

        // Set up IzzyBotResponse
        solesonicChatResponse = new SolesonicChatResponse(chatId, chatMessage);

        // Set up MockMvc
        mockMvc = MockMvcBuilders.standaloneSetup(chatController).build();
    }

    @Test
    void testCreate() throws Exception {
        // Arrange
        when(chatService.create(eq(userId), any(ChatRequest.class))).thenReturn(solesonicChatResponse);

        // Act & Assert
        mockMvc.perform(post("/chats/users/{userId}", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(chatRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(chatId.toString()))
                .andExpect(jsonPath("$.message.message").value("Hello, how can I help you?"));
    }

    @Test
    void testUpdate() throws Exception {
        // Arrange
        when(chatService.update(eq(chatId), any(ChatRequest.class))).thenReturn(solesonicChatResponse);

        // Act & Assert
        mockMvc.perform(put("/chats/{chatId}", chatId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(chatRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(chatId.toString()))
                .andExpect(jsonPath("$.message.message").value("Hello, how can I help you?"));
    }

    @Test
    void testGetUserChats() throws Exception {
        // Arrange
        List<Chat> chats = new ArrayList<>();
        List<ChatMessage> chatMessages = new ArrayList<>();
        chatMessages.add(chatMessage);
        chat.setChatMessages(chatMessages);
        chats.add(chat);

        when(chatService.getByUserId(userId)).thenReturn(chats);

        // Act & Assert
        mockMvc.perform(get("/chats/users/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(chatId.toString()))
                .andExpect(jsonPath("$[0].userId").value(userId.toString()))
                .andExpect(jsonPath("$[0].chatMessages[0].message").value("Hello, how can I help you?"));
    }

    @Test
    void testGet() throws Exception {
        // Arrange
        List<ChatMessage> chatMessages = new ArrayList<>();
        chatMessages.add(chatMessage);
        chat.setChatMessages(chatMessages);

        when(chatService.get(chatId)).thenReturn(chat);

        // Act & Assert
        mockMvc.perform(get("/chats/{chatId}", chatId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(chatId.toString()))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.chatMessages[0].message").value("Hello, how can I help you?"));
    }

    @Test
    void testCreateJsonSerialization() throws Exception {
        // Arrange
        when(chatService.create(eq(userId), any(ChatRequest.class))).thenReturn(solesonicChatResponse);

        // Create a ChatRequest with a specific message
        String requestMessage = "This is a test message for JSON serialization";
        ChatRequest testRequest = new ChatRequest(requestMessage);
        String requestJson = objectMapper.writeValueAsString(testRequest);

        // Act
        MvcResult result = mockMvc.perform(post("/chats/users/{userId}", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        // Assert
        String responseJson = result.getResponse().getContentAsString();
        assertNotNull(responseJson, "Response JSON should not be null");

        // Deserialize the response JSON back to an IzzyBotResponse object
        SolesonicChatResponse responseObject = objectMapper.readValue(responseJson, SolesonicChatResponse.class);

        // Verify the deserialized object
        assertNotNull(responseObject, "Deserialized response object should not be null");
        assertEquals(chatId, responseObject.id(), "Chat ID should match");
        assertNotNull(responseObject.message(), "Message should not be null");
        assertEquals("Hello, how can I help you?", responseObject.message().getMessage(), "Message content should match");
    }

    @Test
    void testUpdateJsonSerialization() throws Exception {
        // Arrange
        when(chatService.update(eq(chatId), any(ChatRequest.class))).thenReturn(solesonicChatResponse);

        // Create a ChatRequest with a specific message
        String requestMessage = "This is a test message for JSON serialization in update";
        ChatRequest testRequest = new ChatRequest(requestMessage);
        String requestJson = objectMapper.writeValueAsString(testRequest);

        // Act
        MvcResult result = mockMvc.perform(put("/chats/{chatId}", chatId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        // Assert
        String responseJson = result.getResponse().getContentAsString();
        assertNotNull(responseJson, "Response JSON should not be null");

        // Deserialize the response JSON back to an IzzyBotResponse object
        SolesonicChatResponse responseObject = objectMapper.readValue(responseJson, SolesonicChatResponse.class);

        // Verify the deserialized object
        assertNotNull(responseObject, "Deserialized response object should not be null");
        assertEquals(chatId, responseObject.id(), "Chat ID should match");
        assertNotNull(responseObject.message(), "Message should not be null");
        assertEquals("Hello, how can I help you?", responseObject.message().getMessage(), "Message content should match");
    }
}
