package com.solesonic.service.redis;

import java.util.UUID;

/**
 * A run whose {@code RUN_STARTED} frame is published. {@code messageId} is the persisted user
 * message's.
 */
public record StartedRun(UUID runId, UUID messageId) {
}
