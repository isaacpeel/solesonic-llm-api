package com.solesonic.service.memory;

import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.model.memory.UserMemoryEvidence;
import com.solesonic.model.memory.UserMemorySummary;
import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.repository.memory.ChatMemoryReflectionRepository;
import com.solesonic.repository.memory.UserMemoryEvidenceRepository;
import com.solesonic.repository.memory.UserMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.MessageType;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserMemoryRememberTest {
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();

    @Mock
    private UserMemoryRepository userMemoryRepository;

    @Mock
    private UserMemoryEvidenceRepository userMemoryEvidenceRepository;

    @Mock
    private ChatMemoryReflectionRepository chatMemoryReflectionRepository;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private UserMemoryService userMemoryService;

    private final ChatMessage issuingMessage = new ChatMessage();

    @BeforeEach
    void setUp() {
        userMemoryService = UserMemoryServiceFixture.service(userMemoryRepository, userMemoryEvidenceRepository,
                chatMemoryReflectionRepository, chatRepository, chatMessageRepository, true);

        issuingMessage.setId(UUID.randomUUID());
        issuingMessage.setChatId(CHAT_ID);
        issuingMessage.setMessageType(MessageType.USER);
        issuingMessage.setTimestamp(ZonedDateTime.now());

        lenient().when(userMemoryRepository.save(any(UserMemory.class))).thenAnswer(invocation -> {
            UserMemory memory = invocation.getArgument(0);

            if (memory.getId() == null) {
                memory.setId(UUID.randomUUID());
            }

            return memory;
        });
    }

    /**
     * The owner is the chat's owner, and the evidence is the message that issued the command, so
     * deleting the conversation forgets a remembered memory like any other.
     */
    @Test
    void remembersForTheChatOwnerWithTheIssuingMessageAsEvidence() {
        ownedChat();
        when(chatMessageRepository.findFirstByChatIdAndMessageTypeOrderByTimestampDesc(CHAT_ID, MessageType.USER))
                .thenReturn(Optional.of(issuingMessage));

        UserMemorySummary summary = userMemoryService.remember(CHAT_ID, MemoryKind.PREFERENCE, " Units.Preference ", "Prefers metric units");

        ArgumentCaptor<UserMemory> saved = ArgumentCaptor.forClass(UserMemory.class);
        verify(userMemoryRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(OWNER_ID);
        assertThat(saved.getValue().getOrigin()).isEqualTo(MemoryOrigin.REMEMBERED);
        assertThat(saved.getValue().getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(saved.getValue().getConfidence()).isEqualTo(1.0);
        assertThat(saved.getValue().getMemoryKey()).isEqualTo("units.preference");

        ArgumentCaptor<UserMemoryEvidence> evidence = ArgumentCaptor.forClass(UserMemoryEvidence.class);
        verify(userMemoryEvidenceRepository).save(evidence.capture());
        assertThat(evidence.getValue().getUserId()).isEqualTo(OWNER_ID);
        assertThat(evidence.getValue().getChatId()).isEqualTo(CHAT_ID);
        assertThat(evidence.getValue().getChatMessageId()).isEqualTo(issuingMessage.getId());
        assertThat(summary.chatIds()).containsExactly(CHAT_ID);
    }

    @Test
    void supersedesAnActiveMemoryWithTheSameKeyRetiringItFirst() {
        UserMemory holder = new UserMemory();
        holder.setId(UUID.randomUUID());
        holder.setUserId(OWNER_ID);
        holder.setStatus(MemoryStatus.ACTIVE);
        holder.setMemoryKey("units.preference");

        ownedChat();
        when(chatMessageRepository.findFirstByChatIdAndMessageTypeOrderByTimestampDesc(CHAT_ID, MessageType.USER))
                .thenReturn(Optional.of(issuingMessage));
        when(userMemoryRepository.findByUserIdAndMemoryKeyAndStatus(OWNER_ID, "units.preference", MemoryStatus.ACTIVE))
                .thenReturn(Optional.of(holder));

        userMemoryService.remember(CHAT_ID, MemoryKind.PREFERENCE, "units.preference", "Prefers imperial units");

        ArgumentCaptor<UserMemory> saved = ArgumentCaptor.forClass(UserMemory.class);
        InOrder order = inOrder(userMemoryRepository);
        order.verify(userMemoryRepository).saveAndFlush(holder);
        order.verify(userMemoryRepository).save(saved.capture());

        assertThat(holder.getStatus()).isEqualTo(MemoryStatus.SUPERSEDED);
        assertThat(saved.getValue().getSupersedes()).isEqualTo(holder.getId());
    }

    @Test
    void refusesWhenTheChatDoesNotExist() {
        when(chatRepository.lockById(CHAT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userMemoryService.remember(CHAT_ID, MemoryKind.FACT, null, "Has two kids"))
                .isInstanceOf(IllegalStateException.class);

        verify(userMemoryRepository, never()).save(any(UserMemory.class));
    }

    private void ownedChat() {
        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        chat.setUserId(OWNER_ID);

        when(chatRepository.lockById(CHAT_ID)).thenReturn(Optional.of(chat));
    }
}
