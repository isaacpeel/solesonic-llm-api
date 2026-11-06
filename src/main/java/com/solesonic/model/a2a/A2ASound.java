package com.solesonic.model.a2a;

/**
 * An audio clip an agent sent with its reply. {@code cueId} is the artifact's id, which tells two
 * cues of the same clip apart.
 */
public record A2ASound(String cueId, byte[] bytes, String mimeType, String cue, boolean playOnce) {
}
