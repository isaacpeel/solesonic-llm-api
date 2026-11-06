package com.solesonic.model.session;

import java.util.UUID;

/**
 * {@code name} is the name the agent knows the participant by.
 */
public record SessionParticipantSummary(UUID userId, int seat, String name, String username, boolean left) {
}
