package com.solesonic.model.interest;

import java.util.List;

/**
 * What the discovery model returns. Untrusted until {@code InterestCandidateValidator} has checked it
 * against the owner's own memories.
 */
public record InterestCandidates(List<InterestCandidate> candidates) {
}
