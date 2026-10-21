package com.solesonic.model.interest;

import java.util.List;
import java.util.UUID;

public record InterestCandidate(String topicKey,
                                String label,
                                String query,
                                String rationale,
                                Double confidence,
                                List<UUID> memoryIds) {
}
