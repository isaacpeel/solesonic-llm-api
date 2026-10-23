package com.solesonic.service.memory;

import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.ReflectionResult;
import com.solesonic.model.memory.ReflectionResult.MemoryAddition;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.repository.memory.MemoryEmbeddingRepository.MemorySimilarity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReflectionDeduplicatorTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID EVIDENCE_ID = UUID.randomUUID();

    @Mock
    private MemoryEmbeddingService memoryEmbeddingService;

    private ReflectionDeduplicator reflectionDeduplicator() {
        return new ReflectionDeduplicator(memoryEmbeddingService, 0.9);
    }

    @Test
    void aMoreDetailedRepeatOfAnActiveMemorySupersedesIt() {
        UserMemory existing = memory(MemoryStatus.ACTIVE, "The user has an Android phone", "android.phone");
        nearest(existing, 0.93);

        ReflectionResult result = reflectionDeduplicator().deduplicate(USER_ID,
                additions(addition("phone.model", "The user has a Google Pixel 8 Android phone")), List.of(existing));

        assertThat(result.add()).isEmpty();
        assertThat(result.supersede()).singleElement()
                .satisfies(supersession -> {
                    assertThat(supersession.memoryId()).isEqualTo(existing.getId());
                    assertThat(supersession.content()).isEqualTo("The user has a Google Pixel 8 Android phone");
                    assertThat(supersession.evidenceMessageIds()).containsExactly(EVIDENCE_ID);
                });
    }

    @Test
    void aRepeatThatAddsNothingConfirmsTheMemory() {
        UserMemory existing = memory(MemoryStatus.ACTIVE, "The user has two daughters, aged 7 and 9", "family.daughters");
        nearest(existing, 0.91);

        ReflectionResult result = reflectionDeduplicator().deduplicate(USER_ID,
                additions(addition("family.has.daughter", "The user has a daughter")), List.of(existing));

        assertThat(result.add()).isEmpty();
        assertThat(result.supersede()).isEmpty();
        assertThat(result.confirm()).singleElement()
                .satisfies(confirmation -> assertThat(confirmation.memoryId()).isEqualTo(existing.getId()));
    }

    /**
     * Saying a pending memory again is what activates it, so a repeat of one is always a confirmation:
     * superseding would start it over with this conversation's evidence alone.
     */
    @Test
    void aRepeatOfAPendingMemoryConfirmsItEvenWhenMoreDetailed() {
        UserMemory pending = memory(MemoryStatus.PENDING, "The user prefers metric", "units.preference");
        nearest(pending, 0.95);

        ReflectionResult result = reflectionDeduplicator().deduplicate(USER_ID,
                additions(addition("units", "The user prefers metric units for everything")), List.of(pending));

        assertThat(result.supersede()).isEmpty();
        assertThat(result.confirm()).singleElement()
                .satisfies(confirmation -> assertThat(confirmation.memoryId()).isEqualTo(pending.getId()));
    }

    @Test
    void anAdditionBelowTheThresholdIsKept() {
        UserMemory existing = memory(MemoryStatus.ACTIVE, "The user lives in Leeds", "home.city");
        nearest(existing, 0.7);

        MemoryAddition addition = addition("vehicle", "The user owns a 2014 Subaru WRX");

        ReflectionResult result = reflectionDeduplicator().deduplicate(USER_ID, additions(addition), List.of(existing));

        assertThat(result.add()).containsExactly(addition);
        assertThat(result.supersede()).isEmpty();
        assertThat(result.confirm()).isEmpty();
    }

    /**
     * A repeated key is the validator's to resolve, so it is never looked up here.
     */
    @Test
    void anAdditionNamingAnExistingKeyIsLeftForTheValidator() {
        UserMemory existing = memory(MemoryStatus.ACTIVE, "The user lives in Leeds", "home.city");
        ReflectionResult proposed = additions(addition(" Home.City ", "The user lives in York"));

        ReflectionResult result = reflectionDeduplicator().deduplicate(USER_ID, proposed, List.of(existing));

        assertThat(result).isSameAs(proposed);
        verify(memoryEmbeddingService, never()).nearestLive(any(UUID.class), anyList());
    }

    @Test
    void aFailedSearchLeavesTheProposalUnchanged() {
        UserMemory existing = memory(MemoryStatus.ACTIVE, "The user lives in Leeds", "home.city");
        ReflectionResult proposed = additions(addition("home.town", "The user lives in Leeds"));

        when(memoryEmbeddingService.nearestLive(eq(USER_ID), anyList())).thenThrow(new IllegalStateException("embedding server down"));

        assertThat(reflectionDeduplicator().deduplicate(USER_ID, proposed, List.of(existing))).isSameAs(proposed);
    }

    private void nearest(UserMemory memory, double similarity) {
        when(memoryEmbeddingService.nearestLive(eq(USER_ID), anyList()))
                .thenReturn(List.of(Optional.of(new MemorySimilarity(memory.getId(), similarity))));
    }

    private static ReflectionResult additions(MemoryAddition addition) {
        return new ReflectionResult(List.of(addition), null, null);
    }

    private static MemoryAddition addition(String key, String content) {
        return new MemoryAddition(MemoryKind.FACT, key, content, 0.9, List.of(EVIDENCE_ID));
    }

    private static UserMemory memory(MemoryStatus status, String content, String memoryKey) {
        UserMemory memory = new UserMemory();
        memory.setId(UUID.randomUUID());
        memory.setUserId(USER_ID);
        memory.setKind(MemoryKind.FACT);
        memory.setOrigin(MemoryOrigin.INFERRED);
        memory.setStatus(status);
        memory.setMemoryKey(memoryKey);
        memory.setContent(content);
        memory.setConfidence(0.9);
        return memory;
    }
}
