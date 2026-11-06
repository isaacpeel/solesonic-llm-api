package com.solesonic.service.memory;

import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.ReflectionResult;
import com.solesonic.model.memory.ReflectionResult.MemoryAddition;
import com.solesonic.model.memory.ReflectionResult.MemoryConfirmation;
import com.solesonic.model.memory.ReflectionResult.MemorySupersession;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.repository.memory.MemoryEmbeddingRepository.MemorySimilarity;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a proposed addition that says what a live memory already says into an operation on that
 * memory, before validation: a confirmation, or — when the proposal is the more detailed of the two
 * and the memory is active — a supersession. Keys only catch a repeat the model names the same way;
 * "has an Android phone" and "has a Pixel 8" are the same subject under two keys.
 * <p>
 * The validator still checks every rewritten operation, so this can only narrow what is learned.
 * When the similarity search fails, the proposals go to validation unchanged.
 */
@Component
public class ReflectionDeduplicator {
    private static final Logger log = LoggerFactory.getLogger(ReflectionDeduplicator.class);

    private final MemoryEmbeddingService memoryEmbeddingService;
    private final double duplicateSimilarity;

    public ReflectionDeduplicator(MemoryEmbeddingService memoryEmbeddingService,
                                  @Value("${solesonic.llm.memory.reflection.duplicate-similarity}") double duplicateSimilarity) {
        this.memoryEmbeddingService = memoryEmbeddingService;
        this.duplicateSimilarity = duplicateSimilarity;
    }

    public ReflectionResult deduplicate(UUID userId, ReflectionResult result, List<UserMemory> memories) {
        List<MemoryAddition> additions = listOrEmpty(result.add());

        if (additions.isEmpty() || memories.isEmpty()) {
            return result;
        }

        Map<UUID, UserMemory> memoriesById = memories.stream()
                .filter(memory -> userId.equals(memory.getUserId()))
                .collect(Collectors.toMap(UserMemory::getId, Function.identity()));

        Set<String> keys = memoriesById.values().stream()
                .map(UserMemory::getMemoryKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<MemoryAddition> candidates = additions.stream()
                .filter(addition -> StringUtils.isNotBlank(addition.content()))
                .filter(addition -> !keys.contains(UserMemoryService.normalizeKey(addition.key())))
                .toList();

        if (candidates.isEmpty()) {
            return result;
        }

        List<Optional<MemorySimilarity>> nearest;

        try {
            nearest = memoryEmbeddingService.nearestLive(userId, candidates.stream().map(MemoryAddition::content).toList());
        } catch (RuntimeException exception) {
            log.warn("Could not compare proposed memories for user {} with existing ones ({})",
                    userId, exception.getClass().getSimpleName());
            return result;
        }

        List<MemoryAddition> kept = new ArrayList<>(additions);
        List<MemorySupersession> supersessions = new ArrayList<>(listOrEmpty(result.supersede()));
        List<MemoryConfirmation> confirmations = new ArrayList<>(listOrEmpty(result.confirm()));

        for (int index = 0; index < candidates.size(); index++) {
            MemoryAddition addition = candidates.get(index);

            Optional<UserMemory> duplicateOf = nearest.get(index)
                    .filter(match -> match.similarity() >= duplicateSimilarity)
                    .map(match -> memoriesById.get(match.id()));

            if (duplicateOf.isEmpty()) {
                continue;
            }

            UserMemory existing = duplicateOf.get();
            kept.remove(addition);

            if (existing.getStatus() == MemoryStatus.ACTIVE && addition.content().trim().length() > existing.getContent().length()) {
                supersessions.add(new MemorySupersession(existing.getId(), addition.content(), addition.confidence(),
                        addition.evidenceMessageIds()));
            } else {
                confirmations.add(new MemoryConfirmation(existing.getId(), addition.evidenceMessageIds()));
            }
        }

        return new ReflectionResult(kept, supersessions, confirmations);
    }

    private static <T> List<T> listOrEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
