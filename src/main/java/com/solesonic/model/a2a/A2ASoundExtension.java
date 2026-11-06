package com.solesonic.model.a2a;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.Part;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The A2A extension through which an agent sends a sound to play with its reply: an artifact holding
 * one audio {@link FilePart} with inline bytes, cued by its {@code solesonic/sound} metadata. The
 * client names it on every message; the agent sends sounds only when it sees it.
 */
public final class A2ASoundExtension {
    private static final Logger log = LoggerFactory.getLogger(A2ASoundExtension.class);

    public static final String URI = "urn:solesonic:a2a:extension:sound:v1";
    public static final String ARTIFACT_NAME = "sound";
    public static final String METADATA_KEY = "solesonic/sound";

    private static final String AUDIO = "audio";
    private static final String CUE = "cue";
    private static final String PLAY_ONCE = "playOnce";
    private static final Pattern SAFE_CUE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern SAFE_CUE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private A2ASoundExtension() {
    }

    /**
     * The artifact's first audio file. The size is checked on the encoded length before anything is
     * decoded, so an oversized clip is refused without being allocated.
     */
    public static Optional<A2ASound> sound(Artifact artifact, long maxBytes) {

        for (Part<?> part : artifact.parts()) {
            if (part instanceof FilePart filePart && filePart.file() instanceof FileWithBytes file) {
                Optional<String> mimeType = audioType(file.mimeType());

                if (mimeType.isPresent()) {
                    if (!SAFE_CUE_ID.matcher(artifact.artifactId()).matches()) {
                        log.warn("Ignoring an A2A sound whose artifact id cannot be sent to a client");
                        return Optional.empty();
                    }

                    Map<?, ?> metadata = soundMetadata(artifact);

                    return decode(file.bytes(), maxBytes).map(bytes -> new A2ASound(artifact.artifactId(), bytes,
                            mimeType.get(), cue(artifact, metadata), playOnce(metadata)));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Served back as the clip's {@code Content-Type}, so only a well-formed {@code audio/*} type is
     * accepted, and only its type and subtype are kept.
     */
    private static Optional<String> audioType(String mimeType) {
        if (StringUtils.isBlank(mimeType)) {
            return Optional.empty();
        }

        try {
            MediaType mediaType = MediaType.parseMediaType(mimeType);

            if (!AUDIO.equals(mediaType.getType()) || mediaType.isWildcardSubtype()) {
                return Optional.empty();
            }

            return Optional.of(mediaType.getType() + "/" + mediaType.getSubtype());
        } catch (InvalidMediaTypeException exception) {
            log.warn("Ignoring an A2A file with an unreadable media type");
            return Optional.empty();
        }
    }

    private static Optional<byte[]> decode(String encoded, long maxBytes) {
        if (StringUtils.isEmpty(encoded)) {
            return Optional.empty();
        }

        if (encoded.length() > (maxBytes + 2) / 3 * 4) {
            log.warn("Ignoring an A2A sound over {} bytes", maxBytes);
            return Optional.empty();
        }

        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);

            if (bytes.length == 0 || bytes.length > maxBytes) {
                log.warn("Ignoring an A2A sound that is empty or over {} bytes", maxBytes);
                return Optional.empty();
            }

            return Optional.of(bytes);
        } catch (IllegalArgumentException exception) {
            log.warn("Ignoring an A2A sound that is not base64");
            return Optional.empty();
        }
    }

    private static Map<?, ?> soundMetadata(Artifact artifact) {
        if (artifact.metadata() != null && artifact.metadata().get(METADATA_KEY) instanceof Map<?, ?> metadata) {
            return metadata;
        }

        return Map.of();
    }

    private static String cue(Artifact artifact, Map<?, ?> metadata) {
        if (metadata.get(CUE) instanceof String cue && SAFE_CUE.matcher(cue).matches()) {
            return cue;
        }

        if (artifact.name() != null && SAFE_CUE.matcher(artifact.name()).matches()) {
            return artifact.name();
        }

        return ARTIFACT_NAME;
    }

    private static boolean playOnce(Map<?, ?> metadata) {
        return !(metadata.get(PLAY_ONCE) instanceof Boolean playOnce) || playOnce;
    }
}
