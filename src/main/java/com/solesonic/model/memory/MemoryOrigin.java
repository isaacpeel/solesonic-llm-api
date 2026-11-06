package com.solesonic.model.memory;

/**
 * Who created a memory. Reflection may only ever supersede or confirm an {@link #INFERRED} one:
 * what the user set deliberately is changed only by the user.
 */
public enum MemoryOrigin {
    INFERRED,
    REMEMBERED,
    AUTHORED;

    public boolean userSet() {
        return this != INFERRED;
    }
}
