package com.solesonic.service.interest;

import java.util.List;
import java.util.UUID;

/**
 * A candidate that passed {@link InterestCandidateValidator}: every field single-line and within its
 * limit, and every memory id one of the owner's own.
 */
public record ValidCandidate(String topicKey,
                             String label,
                             String query,
                             String rationale,
                             double confidence,
                             List<UUID> memoryIds) {
}
