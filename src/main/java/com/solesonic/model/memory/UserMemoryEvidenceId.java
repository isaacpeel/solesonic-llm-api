package com.solesonic.model.memory;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class UserMemoryEvidenceId implements Serializable {
    private UUID memoryId;

    private UUID chatMessageId;

    public UUID getMemoryId() {
        return memoryId;
    }

    public UUID getChatMessageId() {
        return chatMessageId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof UserMemoryEvidenceId that)) {
            return false;
        }

        return Objects.equals(memoryId, that.memoryId) && Objects.equals(chatMessageId, that.chatMessageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(memoryId, chatMessageId);
    }
}
