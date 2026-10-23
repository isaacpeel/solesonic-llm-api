package com.solesonic.service.memory;

import com.solesonic.model.memory.ChatMemoryReflection;
import com.solesonic.model.memory.ReflectionStatus;
import com.solesonic.repository.memory.ChatMemoryReflectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMemoryReflectionServiceTest {
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final long DORMANT_DAYS = 30;

    @Mock
    private ChatMemoryReflectionRepository chatMemoryReflectionRepository;

    private ChatMemoryReflectionService chatMemoryReflectionService;

    @BeforeEach
    void setUp() {
        chatMemoryReflectionService = new ChatMemoryReflectionService(chatMemoryReflectionRepository, DORMANT_DAYS);
    }

    /**
     * Seeding first is what makes the backfill retroactive: a conversation that predates the feature
     * has no row until this gives it one.
     */
    @Test
    void claimSeedsMissingRowsThenMarksTheClaimedOnesInProgress() {
        ZonedDateTime idleBefore = ZonedDateTime.now().minusMinutes(10);
        ChatMemoryReflection reflection = row(ReflectionStatus.PENDING, 0);

        when(chatMemoryReflectionRepository.findClaimable(eq(idleBefore), any(), eq(5))).thenReturn(List.of(reflection));
        when(chatMemoryReflectionRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ChatMemoryReflection> claimed = chatMemoryReflectionService.claim(idleBefore, 5);

        InOrder order = inOrder(chatMemoryReflectionRepository);
        order.verify(chatMemoryReflectionRepository).seedMissing(any());
        order.verify(chatMemoryReflectionRepository).findClaimable(eq(idleBefore), any(), eq(5));

        assertThat(claimed).singleElement()
                .extracting(ChatMemoryReflection::getStatus)
                .isEqualTo(ReflectionStatus.IN_PROGRESS);
    }

    /**
     * Seeding and claiming skip the same users, judged against the same instant: a user is either
     * active for the whole claim or not at all.
     */
    @Test
    void seedingAndClaimingSkipUsersQuietForTheDormantWindow() {
        ZonedDateTime idleBefore = ZonedDateTime.now().minusMinutes(10);
        ArgumentCaptor<ZonedDateTime> seededSince = ArgumentCaptor.forClass(ZonedDateTime.class);
        ArgumentCaptor<ZonedDateTime> claimedSince = ArgumentCaptor.forClass(ZonedDateTime.class);

        when(chatMemoryReflectionRepository.findClaimable(eq(idleBefore), claimedSince.capture(), eq(5))).thenReturn(List.of());
        when(chatMemoryReflectionRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        chatMemoryReflectionService.claim(idleBefore, 5);

        verify(chatMemoryReflectionRepository).seedMissing(seededSince.capture());

        assertThat(seededSince.getValue()).isEqualTo(claimedSince.getValue());
        assertThat(seededSince.getValue())
                .isBetween(ZonedDateTime.now().minusDays(DORMANT_DAYS).minusMinutes(1), ZonedDateTime.now().minusDays(DORMANT_DAYS));
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
