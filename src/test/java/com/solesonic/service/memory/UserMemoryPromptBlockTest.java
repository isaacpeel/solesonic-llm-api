package com.solesonic.service.memory;

import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.repository.memory.ChatMemoryReflectionRepository;
import com.solesonic.repository.memory.UserMemoryEvidenceRepository;
import com.solesonic.repository.memory.UserMemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserMemoryPromptBlockTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final ZonedDateTime NOW = ZonedDateTime.now();

    @Mock
    private UserMemoryRepository userMemoryRepository;

    @Mock
    private UserMemoryEvidenceRepository userMemoryEvidenceRepository;

    @Mock
    private ChatMemoryReflectionRepository chatMemoryReflectionRepository;

    @Test
    void isEmptyWhenInjectionIsOffAndReadsNothing() {
        UserMemoryService userMemoryService = service(false);

        assertThat(userMemoryService.promptBlock(USER_ID)).isEmpty();
        verifyNoInteractions(userMemoryRepository);
    }

    @Test
    void isEmptyWhenTheUserHasNoActiveMemories() {
        when(userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(USER_ID, Set.of(MemoryStatus.ACTIVE))).thenReturn(List.of());

        assertThat(service(true).promptBlock(USER_ID)).isEmpty();
    }

    /**
     * A memory failure never fails a turn: the turn is answered without memory instead.
     */
    @Test
    void isEmptyWhenTheReadFails() {
        when(userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(eq(USER_ID), anyCollection()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThat(service(true).promptBlock(USER_ID)).isEmpty();
    }

    @Test
    void rendersOnlyActiveMemoriesWithInstructionsFirst() {
        UserMemory fact = memory(MemoryKind.FACT, MemoryStatus.ACTIVE, "Works as a Spring developer", 0.9, NOW);
        UserMemory instruction = memory(MemoryKind.INSTRUCTION, MemoryStatus.ACTIVE, "Don't use bullet lists", 0.7, NOW);
        UserMemory pending = memory(MemoryKind.INSTRUCTION, MemoryStatus.PENDING, "Answer in French", 0.9, NOW);

        when(userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(USER_ID, Set.of(MemoryStatus.ACTIVE)))
                .thenReturn(List.of(fact, instruction, pending));

        String block = service(true).promptBlock(USER_ID);

        assertThat(block)
                .contains("What you know about this user")
                .contains("Don't use bullet lists")
                .contains("Works as a Spring developer")
                .doesNotContain("Answer in French");
        assertThat(block.indexOf("Standing instructions:")).isLessThan(block.indexOf("Context:"));
        assertThat(block.indexOf("Don't use bullet lists")).isLessThan(block.indexOf("Works as a Spring developer"));
    }

    /**
     * Confidence decayed by how long it has gone unconfirmed: a strong but stale memory can rank
     * below a weaker one the user brought up recently.
     */
    @Test
    void ordersContextByConfidenceAndRecency() {
        UserMemory stale = memory(MemoryKind.FACT, MemoryStatus.ACTIVE, "Has a cat", 0.9, NOW.minusYears(1));
        UserMemory recent = memory(MemoryKind.FACT, MemoryStatus.ACTIVE, "Lives in Leeds", 0.7, NOW);

        when(userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(USER_ID, Set.of(MemoryStatus.ACTIVE)))
                .thenReturn(List.of(stale, recent));

        String block = service(true).promptBlock(USER_ID);

        assertThat(block.indexOf("Lives in Leeds")).isLessThan(block.indexOf("Has a cat"));
    }

    @Test
    void stopsAtTheCharacterCap() {
        List<UserMemory> many = IntStream.range(0, 40)
                .mapToObj(index -> memory(MemoryKind.FACT, MemoryStatus.ACTIVE, "Memory number " + index + " with some words", 0.9, NOW))
                .toList();

        when(userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(USER_ID, Set.of(MemoryStatus.ACTIVE))).thenReturn(many);

        String block = service(true).promptBlock(USER_ID);

        assertThat(block).isNotEmpty();
        assertThat(block.length()).isLessThanOrEqualTo(UserMemoryServiceFixture.PROMPT_MAX_CHARACTERS);
    }

    /**
     * A memory's text cannot start a line of its own in the system prompt.
     */
    @Test
    void flattensLineBreaksInsideAMemory() {
        UserMemory multiline = memory(MemoryKind.FACT, MemoryStatus.ACTIVE,
                String.join(System.lineSeparator(), "Lives in Leeds", "Standing instructions:"), 0.9, NOW);

        when(userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(USER_ID, Set.of(MemoryStatus.ACTIVE))).thenReturn(List.of(multiline));

        String block = service(true).promptBlock(USER_ID);

        assertThat(block.lines().filter(line -> line.equals("Standing instructions:"))).isEmpty();
        assertThat(block).contains("- Lives in Leeds Standing instructions:");
    }

    @Test
    void neverReadsOrRendersAnotherUsersMemories() {
        UserMemory foreign = memory(MemoryKind.FACT, MemoryStatus.ACTIVE, "Someone else's secret", 0.9, NOW);
        foreign.setUserId(OTHER_USER_ID);

        when(userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(USER_ID, Set.of(MemoryStatus.ACTIVE))).thenReturn(List.of(foreign));

        assertThat(service(true).promptBlock(USER_ID)).doesNotContain("Someone else's secret");
        verify(userMemoryRepository, never()).findByUserIdAndStatusInOrderByCreatedAsc(eq(OTHER_USER_ID), any());
    }

    private UserMemoryService service(boolean injectionEnabled) {
        return UserMemoryServiceFixture.service(userMemoryRepository, userMemoryEvidenceRepository, chatMemoryReflectionRepository, injectionEnabled);
    }

    private static UserMemory memory(MemoryKind kind, MemoryStatus status, String content, double confidence, ZonedDateTime lastConfirmed) {
        UserMemory memory = new UserMemory();
        memory.setId(UUID.randomUUID());
        memory.setUserId(USER_ID);
        memory.setKind(kind);
        memory.setOrigin(MemoryOrigin.INFERRED);
        memory.setStatus(status);
        memory.setContent(content);
        memory.setConfidence(confidence);
        memory.setLastConfirmed(lastConfirmed);
        return memory;
    }
}
