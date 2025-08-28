package com.solesonic.service.ollama;

import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.repository.ollama.ChatMessageRepository;
import com.solesonic.repository.ollama.ChatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.ai.chat.messages.MessageType.ASSISTANT;

@ExtendWith(MockitoExtension.class)
public class ChatServiceTest {

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private PromptService promptService;

    @InjectMocks
    private ChatService chatService;

    private UUID userId;
    private UUID chatId;
    private Chat chat;
    private ChatMessage chatMessage;

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

        // Set up mocks for PromptService
        lenient().when(promptService.model()).thenReturn("llama3");
        lenient().when(promptService.prompt(any(), any())).thenReturn("Hello, how can I help you?");
    }

    @Test
    void testSave() {
        // Arrange
        when(chatRepository.save(any(Chat.class))).thenReturn(chat);

        // Act
        Chat savedChat = chatService.save(chat);

        // Assert
        assertThat(savedChat).isNotNull();
        assertThat(savedChat.getId()).isEqualTo(chatId);
        assertThat(savedChat.getUserId()).isEqualTo(userId);
        verify(chatRepository).save(any(Chat.class));
    }

    @Test
    void testGetByUserId() {
        // Arrange
        List<Chat> chats = new ArrayList<>();
        chats.add(chat);
        List<ChatMessage> chatMessages = new ArrayList<>();
        chatMessages.add(chatMessage);

        when(chatRepository.findByUserId(userId)).thenReturn(chats);
        when(chatMessageRepository.findByChatId(chatId)).thenReturn(chatMessages);

        // Act
        List<Chat> result = chatService.getByUserId(userId);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getId()).isEqualTo(chatId);
        assertThat(result.getFirst().getChatMessages()).hasSize(1);
        verify(chatRepository).findByUserId(userId);
        verify(chatMessageRepository).findByChatId(chatId);
    }

    @Test
    void testGet() {
        // Arrange
        List<ChatMessage> chatMessages = new ArrayList<>();
        chatMessages.add(chatMessage);

        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));
        when(chatMessageRepository.findByChatId(chatId)).thenReturn(chatMessages);

        // Act
        Chat result = chatService.get(chatId);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(chatId);
        assertThat(result.getChatMessages()).hasSize(1);
        verify(chatRepository).findById(chatId);
        verify(chatMessageRepository).findByChatId(chatId);
    }

    @Test
    void testCreate() {
        // Arrange
        ChatRequest chatRequest = new ChatRequest("Hello");

        when(chatRepository.save(any(Chat.class))).thenReturn(chat);

        // Act
        SolesonicChatResponse response = chatService.create(userId, chatRequest);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(chatId);
        assertThat(response.message().getMessage()).isEqualTo("Hello, how can I help you?");
        verify(chatRepository).save(any(Chat.class));
        verify(promptService).prompt(eq(chatId), eq("Hello"));
        verify(promptService).model();
    }

    @Test
    void testUpdate() {
        // Arrange
        ChatRequest chatRequest = new ChatRequest("How are you?");

        // Act
        SolesonicChatResponse response = chatService.update(chatId, chatRequest);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(chatId);
        assertThat(response.message().getMessage()).isEqualTo("Hello, how can I help you?");
        verify(promptService).prompt(eq(chatId), eq("How are you?"));
        verify(promptService).model();
    }

    @Test
    void testUpdate_RemovesThinkTags() {
        // Arrange
        ChatRequest chatRequest = new ChatRequest("Tell me something with think tags");
        when(promptService.prompt(any(), any())).thenReturn("Hello, <think>this should be removed</think> world!");

        // Act
        SolesonicChatResponse response = chatService.update(chatId, chatRequest);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.message().getMessage()).isEqualTo("Hello,  world!");
        verify(promptService).prompt(eq(chatId), eq("Tell me something with think tags"));
    }
}
