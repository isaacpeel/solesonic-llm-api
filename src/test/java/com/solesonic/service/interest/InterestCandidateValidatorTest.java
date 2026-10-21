package com.solesonic.service.interest;

import com.solesonic.model.interest.InterestCandidate;
import com.solesonic.model.interest.InterestCandidates;
import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.service.interest.DiscoveryRun.DropReason;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InterestCandidateValidatorTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-21T12:00:00Z");

    private final InterestCandidateValidator interestCandidateValidator = new InterestCandidateValidator(InterestSettingsFixture.settings());
    private final UserMemory followsFormulaOne = memory(NOW.minusDays(1));
    private final UserMemory livesInLeeds = memory(NOW.minusDays(5));
    private final DiscoveryRun discoveryRun = new DiscoveryRun();

    /**
     * Only a memory the model was shown can back an offer: another user's, an instruction, a
     * superseded one or one never shown is simply not in that set.
     */
    @Test
    void dropsMemoryIdsTheModelWasNotShownAndACandidateLeftWithNone() {
        UUID unseen = UUID.randomUUID();

        Optional<ValidCandidate> valid = validate(
                candidate("sports.formula-1", 0.9, followsFormulaOne.getId(), unseen),
                candidate("finance.markets", 0.95, unseen));

        assertThat(valid).hasValueSatisfying(candidate -> {
            assertThat(candidate.topicKey()).isEqualTo("sports.formula-1");
            assertThat(candidate.memoryIds()).containsExactly(followsFormulaOne.getId());
        });
        assertThat(discoveryRun.drops()).containsEntry(DropReason.NO_MEMORY, 1);
    }

    @Test
    void dropsATopicThatIsBlockedHoweverItIsSpelled() {
        Optional<ValidCandidate> valid = interestCandidateValidator.validate(List.of(followsFormulaOne), Set.of("sports.formula-1"),
                new InterestCandidates(List.of(candidate("  Sports.Formula_1 ", 0.9, followsFormulaOne.getId()))), discoveryRun);

        assertThat(valid).isEmpty();
        assertThat(discoveryRun.drops()).containsEntry(DropReason.BLOCKED_TOPIC, 1);
    }

    @Test
    void dropsABlankFieldAndALowConfidence() {
        InterestCandidate blankLabel = new InterestCandidate("sports.f1", "  ", "F1 news", "Why", 0.9, List.of(followsFormulaOne.getId()));

        Optional<ValidCandidate> valid = validate(blankLabel, candidate("places.leeds", 0.5, livesInLeeds.getId()));

        assertThat(valid).isEmpty();
        assertThat(discoveryRun.drops()).containsEntry(DropReason.BLANK_FIELD, 1).containsEntry(DropReason.LOW_CONFIDENCE, 1);
    }

    @Test
    void capsAndFlattensEveryField() {
        InterestCandidate candidate = new InterestCandidate("sports.f1", "L".repeat(200), "Q".repeat(500), "R".repeat(600), 1.7,
                List.of(followsFormulaOne.getId()));

        ValidCandidate valid = validate(candidate).orElseThrow();

        assertThat(valid.label()).hasSize(InterestText.MAX_LABEL_CHARACTERS);
        assertThat(valid.query()).hasSize(InterestText.MAX_QUERY_CHARACTERS);
        assertThat(valid.rationale()).hasSize(InterestText.MAX_RATIONALE_CHARACTERS);
        assertThat(valid.confidence()).isEqualTo(1.0);
    }

    @Test
    void picksTheMostConfidentAndBreaksTiesByTheMostRecentlyConfirmedMemory() {
        Optional<ValidCandidate> mostConfident = validate(
                candidate("places.leeds", 0.8, livesInLeeds.getId()),
                candidate("sports.formula-1", 0.9, followsFormulaOne.getId()));

        Optional<ValidCandidate> tie = validate(
                candidate("places.leeds", 0.9, livesInLeeds.getId()),
                candidate("sports.formula-1", 0.9, followsFormulaOne.getId()));

        assertThat(mostConfident.map(ValidCandidate::topicKey)).contains("sports.formula-1");
        assertThat(tie.map(ValidCandidate::topicKey)).contains("sports.formula-1");
    }

    @Test
    void nothingProposedIsNothingOffered() {
        assertThat(interestCandidateValidator.validate(List.of(followsFormulaOne), Set.of(), new InterestCandidates(null), discoveryRun)).isEmpty();
        assertThat(interestCandidateValidator.validate(List.of(followsFormulaOne), Set.of(), null, discoveryRun)).isEmpty();
    }

    private Optional<ValidCandidate> validate(InterestCandidate... candidates) {
        return interestCandidateValidator.validate(List.of(followsFormulaOne, livesInLeeds), Set.of(), new InterestCandidates(List.of(candidates)),
                discoveryRun);
    }

    private static InterestCandidate candidate(String topicKey, double confidence, UUID... memoryIds) {
        return new InterestCandidate(topicKey, "Label", "Query", "Rationale", confidence, List.of(memoryIds));
    }

    static UserMemory memory(ZonedDateTime lastConfirmed) {
        UserMemory memory = new UserMemory();
        memory.setId(UUID.randomUUID());
        memory.setUserId(USER_ID);
        memory.setKind(MemoryKind.FACT);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setContent("content");
        memory.setLastConfirmed(lastConfirmed);
        memory.setUpdated(lastConfirmed);
        return memory;
    }
}
