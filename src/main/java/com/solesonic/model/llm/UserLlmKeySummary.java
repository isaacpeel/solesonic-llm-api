package com.solesonic.model.llm;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * What an administrator sees of a registered key. The key itself is write-only and never appears
 * here.
 */
public record UserLlmKeySummary(UUID userId,
                                String username,
                                String keyHint,
                                ZonedDateTime created,
                                ZonedDateTime updated,
                                UUID updatedBy) {
}
