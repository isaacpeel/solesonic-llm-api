package com.solesonic.service.ollama;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.repository.UserPreferencesRepository;
import com.solesonic.repository.ollama.ChatMessageRepository;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Guards the trailing-USER rule in {@link ChatMessageService#findByChatId(UUID)}.
 * <p>
 * The in-flight user message is persisted before the stream starts, and the chat memory advisor
 * supplies it again as the live user message. If this rule regresses the model sees the current
 * turn twice on every turn — which produces no error, just quietly worse output. That silence is
 * why it gets a dedicated test.
 */
@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    @SuppressWarnings("unused")
    private UserPreferencesRepository userPreferencesRepository;

    @Mock
    @SuppressWarnings("unused")
    private ChatAttachmentService chatAttachmentService;

    @InjectMocks
    private ChatMessageService chatMessageService;

    private final UUID chatId = UUID.randomUUID();

    private ChatMessage chatMessage(MessageType messageType, String text) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setChatId(chatId);
        chatMessage.setMessageType(messageType);
        chatMessage.setMessage(text);

        return chatMessage;
    }

    @Test
    void findByChatIdDropsTrailingUserMessage() {
        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                chatMessage(MessageType.USER, "first question"),
                chatMessage(MessageType.ASSISTANT, "first answer"),
                chatMessage(MessageType.USER, "in-flight question")));

        List<Message> messages = chatMessageService.findByChatId(chatId);

        assertThat(messages).hasSize(2);
        assertThat(messages.getFirst()).isInstanceOf(UserMessage.class);
        assertThat(messages.getFirst().getText()).isEqualTo("first question");
        assertThat(messages.getLast()).isInstanceOf(AssistantMessage.class);
        assertThat(messages.getLast().getText()).isEqualTo("first answer");
    }

    @Test
    void findByChatIdKeepsTrailingAssistantMessage() {
        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                chatMessage(MessageType.USER, "question"),
                chatMessage(MessageType.ASSISTANT, "answer")));

        List<Message> messages = chatMessageService.findByChatId(chatId);

        assertThat(messages).hasSize(2);
        assertThat(messages.getLast()).isInstanceOf(AssistantMessage.class);
    }

    @Test
    void findByChatIdReturnsEmptyForSingleUserMessage() {
        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                chatMessage(MessageType.USER, "the only turn so far")));

        List<Message> messages = chatMessageService.findByChatId(chatId);

        assertThat(messages).isEmpty();
    }

    @Test
    void findByChatIdReturnsEmptyForEmptyHistory() {
        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of());

        List<Message> messages = chatMessageService.findByChatId(chatId);

        assertThat(messages).isEmpty();
    }
}
