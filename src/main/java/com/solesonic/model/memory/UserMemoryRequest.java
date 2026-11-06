package com.solesonic.model.memory;

/**
 * What a user may write directly: the text, and what kind of memory it is. Nothing else is
 * client-settable — origin, status and confidence follow from the user having written it.
 */
public record UserMemoryRequest(MemoryKind kind, String content) {
}
