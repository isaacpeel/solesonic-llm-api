package com.solesonic.service.memory;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.UserMemory;

import java.util.List;
import java.util.Map;

/**
 * A reflection result after every code-side check: each operation names only the owner's live
 * memories and cites only {@code USER} messages of the window, and {@code drops} counts what was
 * refused, by reason, so a run can be judged without logging any content.
 */
public record ValidatedReflection(List<Addition> additions,
                                  List<Supersession> supersessions,
                                  List<Confirmation> confirmations,
                                  Map<DropReason, Integer> drops) {

    public static ValidatedReflection empty() {
        return new ValidatedReflection(List.of(), List.of(), List.of(), Map.of());
    }

    public record Addition(MemoryKind kind,
                           String key,
                           String content,
                           double confidence,
                           MemoryStatus status,
                           List<ChatMessage> evidence) {
    }

    public record Supersession(UserMemory target,
                               String content,
                               double confidence,
                               MemoryStatus status,
                               List<ChatMessage> evidence) {
    }

    public record Confirmation(UserMemory target, List<ChatMessage> evidence, boolean activate) {
    }

    public enum DropReason {
        INVALID_EVIDENCE,
        NO_EVIDENCE,
        UNKNOWN_MEMORY,
        USER_SET_MEMORY,
        DUPLICATE_TARGET,
        OLDER_EVIDENCE,
        MISSING_KIND,
        BLANK_CONTENT,
        LOW_CONFIDENCE,
        UNGROUNDED,
        NOT_ABOUT_USER,
        PASTED_EVIDENCE
    }
}
