package com.solesonic.model.image;

/**
 * The whole of what an update may write. Rename only — the bytes and provenance of a generated
 * image never change once written.
 */
public record GeneratedImageUpdateRequest(String name) {
}
