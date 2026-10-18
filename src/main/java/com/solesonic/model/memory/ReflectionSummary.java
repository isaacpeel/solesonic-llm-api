package com.solesonic.model.memory;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One conversation's reflection progress, for the admin status page.
 */
public record ReflectionSummary(UUID chatId,
                                UUID userId,
                                ReflectionStatus status,
                                int attempts,
                                ZonedDateTime reflectedThrough,
                                ZonedDateTime updated) {

    public static ReflectionSummary of(ChatMemoryReflection chatMemoryReflection) {
        return new ReflectionSummary(chatMemoryReflection.getChatId(),
                chatMemoryReflection.getUserId(),
                chatMemoryReflection.getStatus(),
                chatMemoryReflection.getAttempts(),
                chatMemoryReflection.getReflectedThrough(),
                chatMemoryReflection.getUpdated());
    }
}
