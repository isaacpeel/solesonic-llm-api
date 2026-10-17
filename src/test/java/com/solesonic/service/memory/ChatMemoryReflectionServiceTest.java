package com.solesonic.service.memory;

import com.solesonic.model.memory.ChatMemoryReflection;
import com.solesonic.model.memory.ReflectionStatus;
import com.solesonic.repository.memory.ChatMemoryReflectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMemoryReflectionServiceTest {
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private ChatMemoryReflectionRepository chatMemoryReflectionRepository;

    private ChatMemoryReflectionService chatMemoryReflectionService;

    @BeforeEach
    void setUp() {
        chatMemoryReflectionService = new ChatMemoryReflectionService(chatMemoryReflectionRepository);
    }

    /**
     * Seeding first is what makes the backfill retroactive: a conversation that predates the feature
     * has no row until this gives it one.
     */
    @Test
    void claimSeedsMissingRowsThenMarksTheClaimedOnesInProgress() {
        ZonedDateTime idleBefore = ZonedDateTime.now().minusMinutes(10);
        ChatMemoryReflection reflection = row(ReflectionStatus.PENDING, 0);

        when(chatMemoryReflectionRepository.findClaimable(idleBefore, 5)).thenReturn(List.of(reflection));
        when(chatMemoryReflectionRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ChatMemoryReflection> claimed = chatMemoryReflectionService.claim(idleBefore, 5);

        InOrder order = inOrder(chatMemoryReflectionRepository);
        order.verify(chatMemoryReflectionRepository).seedMissing();
        order.verify(chatMemoryReflectionRepository).findClaimable(idleBefore, 5);

        assertThat(claimed).singleElement()
                .extracting(ChatMemoryReflection::getStatus)
                .isEqualTo(ReflectionStatus.IN_PROGRESS);
    }

    @Test
    void aFailureBelowTheLimitLeavesTheChatRetriable() {
        ChatMemoryReflection reflection = row(ReflectionStatus.IN_PROGRESS, 0);

        when(chatMemoryReflectionRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(reflection));

        chatMemoryReflectionService.recordFailure(CHAT_ID, USER_ID, 3);

        assertThat(reflection.getAttempts()).isEqualTo(1);
        assertThat(reflection.getStatus()).isEqualTo(ReflectionStatus.PENDING);
    }

    @Test
    void theLastPermittedFailureMarksTheChatFailed() {
        ChatMemoryReflection reflection = row(ReflectionStatus.IN_PROGRESS, 2);

        when(chatMemoryReflectionRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(reflection));

        chatMemoryReflectionService.recordFailure(CHAT_ID, USER_ID, 3);

        assertThat(reflection.getAttempts()).isEqualTo(3);
        assertThat(reflection.getStatus()).isEqualTo(ReflectionStatus.FAILED);
    }

    private static ChatMemoryReflection row(ReflectionStatus status, int attempts) {
        ChatMemoryReflection reflection = new ChatMemoryReflection();
        reflection.setChatId(CHAT_ID);
        reflection.setUserId(USER_ID);
        reflection.setStatus(status);
        reflection.setAttempts(attempts);
        reflection.setReflectedThrough(ZonedDateTime.now().minusDays(1));
        return reflection;
    }
}
