package com.solesonic.model.memory;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One conversation's reflection progress, for the admin status page. {@code username} is null when
 * the owner has no recorded profile.
 */
public record ReflectionSummary(UUID chatId,
                                UUID userId,
                                String username,
                                ReflectionStatus status,
                                int attempts,
                                ZonedDateTime reflectedThrough,
                                ZonedDateTime updated) {

    public static ReflectionSummary of(ChatMemoryReflection chatMemoryReflection, String username) {
        return new ReflectionSummary(chatMemoryReflection.getChatId(),
                chatMemoryReflection.getUserId(),
                username,
                chatMemoryReflection.getStatus(),
                chatMemoryReflection.getAttempts(),
                chatMemoryReflection.getReflectedThrough(),
                chatMemoryReflection.getUpdated());
    }
}
