package com.solesonic.model.a2a;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class A2ASoundExtensionTest {
    private static final long MAX_BYTES = 1024;
    private static final byte[] CLIP = "ID3-fake-mp3".getBytes(StandardCharsets.UTF_8);

    private static Artifact artifact(Map<String, Object> metadata, Part<?>... parts) {
        return Artifact.builder()
                .artifactId("sound-1")
                .name("sound")
                .parts(parts)
                .metadata(metadata)
                .build();
    }

    private static FilePart audio(String mimeType, byte[] bytes) {
        return new FilePart(new FileWithBytes(mimeType, "theme.mp3", bytes));
    }

    @Test
    void readsAnAudioFileWithItsCue() {
        Artifact artifact = artifact(Map.of(A2ASoundExtension.METADATA_KEY, Map.of("cue", "theme", "playOnce", false)),
                audio("audio/mpeg", CLIP));

        assertThat(A2ASoundExtension.sound(artifact, MAX_BYTES)).hasValueSatisfying(sound -> {
            assertThat(sound.cueId()).isEqualTo("sound-1");
            assertThat(sound.bytes()).isEqualTo(CLIP);
            assertThat(sound.mimeType()).isEqualTo("audio/mpeg");
            assertThat(sound.cue()).isEqualTo("theme");
            assertThat(sound.playOnce()).isFalse();
        });
    }

    @Test
    void aSoundWithNoMetadataIsCuedByTheArtifactNameAndPlaysOnce() {
        assertThat(A2ASoundExtension.sound(artifact(null, audio("audio/mpeg", CLIP)), MAX_BYTES)).hasValueSatisfying(sound -> {
            assertThat(sound.cue()).isEqualTo("sound");
            assertThat(sound.playOnce()).isTrue();
        });
    }

    @Test
    void anUnsafeCueFallsBackToTheArtifactName() {
        Artifact artifact = artifact(Map.of(A2ASoundExtension.METADATA_KEY, Map.of("cue", "<script>")), audio("audio/mpeg", CLIP));

        assertThat(A2ASoundExtension.sound(artifact, MAX_BYTES))
                .hasValueSatisfying(sound -> assertThat(sound.cue()).isEqualTo("sound"));
    }

    @Test
    void theMimeTypeIsNormalized() {
        assertThat(A2ASoundExtension.sound(artifact(null, audio("Audio/MPEG; rate=44100", CLIP)), MAX_BYTES))
                .hasValueSatisfying(sound -> assertThat(sound.mimeType()).isEqualTo("audio/mpeg"));
    }

    @Test
    void aTextArtifactIsNotASound() {
        assertThat(A2ASoundExtension.sound(artifact(null, new TextPart("board")), MAX_BYTES)).isEmpty();
    }

    @Test
    void aFileThatIsNotAudioIsNotASound() {
        assertThat(A2ASoundExtension.sound(artifact(null, audio("text/html", CLIP)), MAX_BYTES)).isEmpty();
        assertThat(A2ASoundExtension.sound(artifact(null, audio("audio/*", CLIP)), MAX_BYTES)).isEmpty();
        assertThat(A2ASoundExtension.sound(artifact(null, audio("not a type", CLIP)), MAX_BYTES)).isEmpty();
    }

    @Test
    void aFileByUriIsNotASound() {
        Artifact artifact = artifact(null, new FilePart(new FileWithUri("audio/mpeg", "theme.mp3", "http://agent/theme.mp3")));

        assertThat(A2ASoundExtension.sound(artifact, MAX_BYTES)).isEmpty();
    }

    @Test
    void anOversizedSoundIsRefused() {
        assertThat(A2ASoundExtension.sound(artifact(null, audio("audio/mpeg", new byte[(int) MAX_BYTES + 1])), MAX_BYTES)).isEmpty();
        assertThat(A2ASoundExtension.sound(artifact(null, audio("audio/mpeg", new byte[(int) MAX_BYTES])), MAX_BYTES)).isPresent();
    }

    @Test
    void aSoundWhoseArtifactIdCannotReachAClientIsRefused() {
        Artifact markup = Artifact.builder().artifactId("<img src=x>").name("sound").parts(audio("audio/mpeg", CLIP)).build();
        Artifact oversized = Artifact.builder().artifactId("s".repeat(129)).name("sound").parts(audio("audio/mpeg", CLIP)).build();

        assertThat(A2ASoundExtension.sound(markup, MAX_BYTES)).isEmpty();
        assertThat(A2ASoundExtension.sound(oversized, MAX_BYTES)).isEmpty();
    }

    @Test
    void anEmptySoundIsRefused() {
        assertThat(A2ASoundExtension.sound(artifact(null, audio("audio/mpeg", new byte[0])), MAX_BYTES)).isEmpty();
    }
}
