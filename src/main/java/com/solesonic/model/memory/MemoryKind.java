package com.solesonic.model.memory;

/**
 * What a memory is, which decides how it is rendered into the system prompt and nothing else:
 * {@link #INSTRUCTION} is a standing instruction, the other two are context.
 */
public enum MemoryKind {
    FACT,
    PREFERENCE,
    INSTRUCTION
}
