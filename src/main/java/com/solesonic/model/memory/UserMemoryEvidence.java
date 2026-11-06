package com.solesonic.model.memory;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One {@code USER} message a memory was learned from. Carries its own {@code userId} so the
 * composite foreign key can refuse evidence pointing at another user's memory.
 */
@Entity
@IdClass(UserMemoryEvidenceId.class)
public class UserMemoryEvidence {
    @Id
    private UUID memoryId;

    @Id
    private UUID chatMessageId;

    private UUID userId;

    private UUID chatId;

    private ZonedDateTime chatMessageTimestamp;

    public UserMemoryEvidence() {
    }

    public UserMemoryEvidence(UUID memoryId, UUID userId, UUID chatId, UUID chatMessageId, ZonedDateTime chatMessageTimestamp) {
        this.memoryId = memoryId;
        this.userId = userId;
        this.chatId = chatId;
        this.chatMessageId = chatMessageId;
        this.chatMessageTimestamp = chatMessageTimestamp;
    }

    public UUID getMemoryId() {
        return memoryId;
    }

    public UUID getChatMessageId() {
        return chatMessageId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getChatId() {
        return chatId;
    }

    public ZonedDateTime getChatMessageTimestamp() {
        return chatMessageTimestamp;
    }
}
