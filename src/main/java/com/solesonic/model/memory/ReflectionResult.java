package com.solesonic.model.memory;

import java.util.List;
import java.util.UUID;

/**
 * What the memory model may propose for one window of one conversation. There is deliberately no
 * removal: the model can replace a memory or leave it alone, never delete one. Nothing here is
 * trusted until {@code ReflectionValidator} has checked it against the owner.
 */
public record ReflectionResult(List<MemoryAddition> add,
                               List<MemorySupersession> supersede,
                               List<MemoryConfirmation> confirm) {

    public record MemoryAddition(MemoryKind kind,
                                 String key,
                                 String content,
                                 double confidence,
                                 List<UUID> evidenceMessageIds) {
    }

    public record MemorySupersession(UUID memoryId,
                                     String content,
                                     double confidence,
                                     List<UUID> evidenceMessageIds) {
    }

    public record MemoryConfirmation(UUID memoryId, List<UUID> evidenceMessageIds) {
    }
}
