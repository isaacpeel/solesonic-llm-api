package com.solesonic.service.memory;

import java.util.UUID;

/**
 * Published when a user's memory content may have changed, so it is embedded once the change has
 * committed rather than waiting for the next {@code MemoryEmbeddingTask} tick.
 */
public record MemoriesChangedEvent(UUID userId) {
}
