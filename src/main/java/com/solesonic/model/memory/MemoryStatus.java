package com.solesonic.model.memory;

/**
 * Only {@link #ACTIVE} memories are injected. An inferred instruction starts {@link #PENDING} until
 * its evidence spans two conversations.
 */
public enum MemoryStatus {
    PENDING,
    ACTIVE,
    SUPERSEDED
}
