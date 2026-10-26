package com.solesonic.model.chat;

/**
 * What a client is told about a sound, under {@code solesonic/sound} in the reply's
 * {@code TEXT_MESSAGE_START} metadata. {@code cueId} identifies this cue and {@code soundId} the
 * clip, which is the same every time it is played. {@code source} has the shape of AG-UI's
 * {@code PartSource}, so moving to an assistant audio part later is a relocation.
 */
public record SoundCue(String cueId, String soundId, String cue, boolean playOnce, PartSource source) {
    public static final String URL_SOURCE = "url";

    public record PartSource(String type, String value, String mimeType) {
    }
}
