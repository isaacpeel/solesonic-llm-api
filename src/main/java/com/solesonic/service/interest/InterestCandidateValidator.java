package com.solesonic.service.interest;

import com.solesonic.config.interest.InterestSettings;
import com.solesonic.model.interest.InterestCandidate;
import com.solesonic.model.interest.InterestCandidates;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.service.interest.DiscoveryRun.DropReason;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The code-side guard on what the discovery model proposes, independent of whether it obeyed the
 * prompt. A memory id counts only if it is one the model was shown — the owner's own active facts and
 * preferences — so a foreign, superseded or instruction memory can never back an offer, and a topic
 * the user already has, is being asked about, or declined is dropped however it was spelled.
 */
@Component
public class InterestCandidateValidator {
    private final double minConfidence;

    public InterestCandidateValidator(InterestSettings interestSettings) {
        this.minConfidence = interestSettings.minConfidence();
    }

    /**
     * @param shown   the memories the model was shown, all the owner's
     * @param blocked topic keys that must not be offered
     * @return the single best candidate, highest confidence first, ties to the one citing the most
     * recently confirmed memory
     */
    public Optional<ValidCandidate> validate(Collection<UserMemory> shown,
                                             Set<String> blocked,
                                             InterestCandidates interestCandidates,
                                             DiscoveryRun discoveryRun) {
        if (interestCandidates == null || interestCandidates.candidates() == null) {
            return Optional.empty();
        }

        Map<UUID, UserMemory> shownById = shown.stream().collect(Collectors.toMap(UserMemory::getId, Function.identity()));

        List<ValidCandidate> valid = interestCandidates.candidates().stream()
                .filter(Objects::nonNull)
                .map(candidate -> valid(candidate, shownById, blocked, discoveryRun))
                .flatMap(Optional::stream)
                .toList();

        return valid.stream().max(Comparator.comparingDouble(ValidCandidate::confidence)
                .thenComparing(candidate -> newestConfirmation(candidate, shownById), Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private Optional<ValidCandidate> valid(InterestCandidate candidate,
                                           Map<UUID, UserMemory> shownById,
                                           Set<String> blocked,
                                           DiscoveryRun discoveryRun) {
        List<UUID> memoryIds = candidate.memoryIds() == null ? List.of() : candidate.memoryIds().stream()
                .filter(shownById::containsKey)
                .distinct()
                .toList();

        if (memoryIds.isEmpty()) {
            discoveryRun.dropped(DropReason.NO_MEMORY);
            return Optional.empty();
        }

        String topicKey = InterestText.topicKey(candidate.topicKey());
        String label = InterestText.singleLine(candidate.label(), InterestText.MAX_LABEL_CHARACTERS);
        String query = InterestText.singleLine(candidate.query(), InterestText.MAX_QUERY_CHARACTERS);
        String rationale = InterestText.singleLine(candidate.rationale(), InterestText.MAX_RATIONALE_CHARACTERS);

        if (topicKey == null || label == null || query == null || rationale == null) {
            discoveryRun.dropped(DropReason.BLANK_FIELD);
            return Optional.empty();
        }

        double confidence = clamp(candidate.confidence());

        if (confidence < minConfidence) {
            discoveryRun.dropped(DropReason.LOW_CONFIDENCE);
            return Optional.empty();
        }

        if (blocked.contains(topicKey)) {
            discoveryRun.dropped(DropReason.BLOCKED_TOPIC);
            return Optional.empty();
        }

        return Optional.of(new ValidCandidate(topicKey, label, query, rationale, confidence, memoryIds));
    }

    private static double clamp(Double confidence) {
        if (confidence == null || confidence.isNaN()) {
            return 0;
        }

        return Math.clamp(confidence, 0.0, 1.0);
    }

    private static ZonedDateTime newestConfirmation(ValidCandidate candidate, Map<UUID, UserMemory> shownById) {
        return candidate.memoryIds().stream()
                .map(shownById::get)
                .map(UserMemory::getLastConfirmed)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }
}
