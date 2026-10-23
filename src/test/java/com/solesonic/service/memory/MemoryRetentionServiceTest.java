package com.solesonic.service.memory;

import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.repository.memory.UserMemoryAdminRepository;
import com.solesonic.repository.memory.UserMemoryRepository;
import com.solesonic.service.interest.InterestOfferWithdrawal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryRetentionServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final ZonedDateTime NOW = ZonedDateTime.now();

    @Mock
    private UserMemoryRepository userMemoryRepository;

    @Mock
    private UserMemoryAdminRepository userMemoryAdminRepository;

    @Mock
    private InterestOfferWithdrawal interestOfferWithdrawal;

    @Captor
    private ArgumentCaptor<Collection<UUID>> deletedCaptor;

    private MemoryRetentionService memoryRetentionService;

    @BeforeEach
    void setUp() {
        memoryRetentionService = new MemoryRetentionService(userMemoryRepository, userMemoryAdminRepository,
                interestOfferWithdrawal, 30, 0.3);

        lenient().when(userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(USER_ID, Set.of(MemoryStatus.SUPERSEDED)))
                .thenReturn(List.of());
    }

    @Test
    void retiresAnUnconfirmedPendingMemoryAndALapsedActiveOne() {
        UserMemory stalePending = memory(MemoryOrigin.INFERRED, MemoryStatus.PENDING, NOW.minusDays(31));
        UserMemory freshPending = memory(MemoryOrigin.INFERRED, MemoryStatus.PENDING, NOW.minusDays(29));
        UserMemory lapsedActive = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, NOW.minusDays(200));
        UserMemory recentActive = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, NOW.minusDays(10));

        live(stalePending, freshPending, lapsedActive, recentActive);

        int retired = memoryRetentionService.retire(USER_ID);

        assertThat(retired).isEqualTo(2);
        verify(userMemoryRepository).deleteByUserIdAndIdIn(eq(USER_ID), deletedCaptor.capture());
        assertThat(deletedCaptor.getValue()).containsExactlyInAnyOrder(stalePending.getId(), lapsedActive.getId());
        verify(interestOfferWithdrawal).withdrawOrphaned(USER_ID);
    }

    @Test
    void neverRetiresWhatTheUserSet() {
        live(memory(MemoryOrigin.AUTHORED, MemoryStatus.ACTIVE, NOW.minusDays(400)),
                memory(MemoryOrigin.REMEMBERED, MemoryStatus.ACTIVE, NOW.minusDays(400)));

        assertThat(memoryRetentionService.retire(USER_ID)).isZero();
        verify(userMemoryRepository, never()).deleteByUserIdAndIdIn(any(), anyCollection());
        verify(interestOfferWithdrawal, never()).withdrawOrphaned(any());
    }

    /**
     * The superseded rows behind a retired memory exist only to be restored if it is forgotten; once
     * it has lapsed they go with it.
     */
    @Test
    void retiresTheSupersededChainBehindALapsedMemory() {
        UserMemory oldest = memory(MemoryOrigin.INFERRED, MemoryStatus.SUPERSEDED, NOW.minusDays(300));
        UserMemory middle = memory(MemoryOrigin.INFERRED, MemoryStatus.SUPERSEDED, NOW.minusDays(250));
        middle.setSupersedes(oldest.getId());
        UserMemory head = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, NOW.minusDays(200));
        head.setSupersedes(middle.getId());

        live(head);
        when(userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(USER_ID, Set.of(MemoryStatus.SUPERSEDED)))
                .thenReturn(List.of(oldest, middle));

        assertThat(memoryRetentionService.retire(USER_ID)).isEqualTo(1);
        verify(userMemoryRepository).deleteByUserIdAndIdIn(eq(USER_ID), deletedCaptor.capture());
        assertThat(deletedCaptor.getValue()).containsExactlyInAnyOrder(head.getId(), middle.getId(), oldest.getId());
    }

    private void live(UserMemory... memories) {
        when(userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(USER_ID, UserMemoryService.LIVE_STATUSES))
                .thenReturn(List.of(memories));
    }

    private static UserMemory memory(MemoryOrigin origin, MemoryStatus status, ZonedDateTime lastConfirmed) {
        UserMemory memory = new UserMemory();
        memory.setId(UUID.randomUUID());
        memory.setUserId(USER_ID);
        memory.setKind(MemoryKind.FACT);
        memory.setOrigin(origin);
        memory.setStatus(status);
        memory.setContent("The user lives in Leeds");
        memory.setConfidence(0.9);
        memory.setLastConfirmed(lastConfirmed);
        return memory;
    }
}
