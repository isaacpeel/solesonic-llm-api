package com.solesonic.service.interest;

import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.service.chat.ChatMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.MessageType;

import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterestChatWriterTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHAT_GROUP_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-21T15:00:00Z");

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private ChatMessageService chatMessageService;

    @Test
    void writesAChatOwnedByTheAgentsUserHoldingExactlyOneAssistantMessage() {
        when(chatRepository.save(any(Chat.class))).thenAnswer(invocation -> {
            Chat chat = invocation.getArgument(0);
            chat.setId(CHAT_ID);
            return chat;
        });

        Chat chat = new InterestChatWriter(chatRepository, chatMessageService).write(USER_ID, "Formula 1 · Oct 21", CHAT_GROUP_ID, "text", NOW);

        assertThat(chat.getUserId()).isEqualTo(USER_ID);
        assertThat(chat.getName()).isEqualTo("Formula 1 · Oct 21");
        assertThat(chat.getChatGroupId()).isEqualTo(CHAT_GROUP_ID);
        assertThat(chat.getGroupSortOrder()).isNull();
        assertThat(chat.getTimestamp()).isEqualTo(NOW);

        ArgumentCaptor<ChatMessage> message = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageService).save(message.capture());
        assertThat(message.getValue().getChatId()).isEqualTo(CHAT_ID);
        assertThat(message.getValue().getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(message.getValue().getMessage()).isEqualTo("text");
    }
}
