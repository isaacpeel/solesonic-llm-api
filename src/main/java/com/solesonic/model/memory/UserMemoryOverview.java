package com.solesonic.model.memory;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One user's memories and reflection progress in counts, for the admin user list. A user appears
 * once they have a memory or a conversation queued for reflection.
 */
public record UserMemoryOverview(UUID userId,
                                 long activeMemories,
                                 long pendingMemories,
                                 long supersededMemories,
                                 long pendingChats,
                                 long inProgressChats,
                                 long completedChats,
                                 long failedChats,
                                 ZonedDateTime lastReflected) {
}
