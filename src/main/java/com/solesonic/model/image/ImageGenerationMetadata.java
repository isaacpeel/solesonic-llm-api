package com.solesonic.model.image;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The image tool's metadata, pulled out of the human-readable text block it returns alongside the
 * image:
 * <pre>
 * Generated with FLUX.1-schnell.
 * Size: 1024x1024
 * Steps: 4
 * Seed: 8339331079448168597
 * Elapsed: 8.2s
 * </pre>
 * Parsed once, here at the boundary, and stored as columns. The blob itself is never persisted or
 * passed downstream — re-parsing prose on every read is how a display bug becomes a data bug.
 * <p>
 * Every field is optional. The tool advertises no output schema, so this text is a presentation
 * detail of another service: a wording change must cost this API a null, not a failed generation.
 */
public record ImageGenerationMetadata(String model,
                                      Long seed,
                                      Integer width,
                                      Integer height,
                                      Integer steps,
                                      Double elapsedSeconds) {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationMetadata.class);

    private static final ImageGenerationMetadata EMPTY =
            new ImageGenerationMetadata(null, null, null, null, null, null);

    private static final Pattern MODEL_PATTERN = Pattern.compile("Generated with\\s+(.+?)\\s*\\.?\\s*$",
            Pattern.MULTILINE);

    /**
     * Both separators are accepted because the tool writes the ASCII {@code x} in its metadata block
     * and the multiplication sign in its final progress message.
     */
    private static final Pattern SIZE_PATTERN = Pattern.compile("Size:\\s*(\\d+)\\s*[x×]\\s*(\\d+)");
    private static final Pattern STEPS_PATTERN = Pattern.compile("Steps:\\s*(\\d+)");
    private static final Pattern SEED_PATTERN = Pattern.compile("Seed:\\s*(\\d+)");
    private static final Pattern ELAPSED_PATTERN = Pattern.compile("Elapsed:\\s*(\\d+(?:\\.\\d+)?)\\s*s");

    public static ImageGenerationMetadata parse(String metadataText) {
        if (StringUtils.isBlank(metadataText)) {
            log.warn("Image generation returned no metadata text; storing the image without provenance");

            return EMPTY;
        }

        Integer width = null;
        Integer height = null;

        Matcher sizeMatcher = SIZE_PATTERN.matcher(metadataText);

        if (sizeMatcher.find()) {
            width = integer(sizeMatcher.group(1));
            height = integer(sizeMatcher.group(2));
        }

        return new ImageGenerationMetadata(
                firstGroup(MODEL_PATTERN, metadataText),
                longValue(firstGroup(SEED_PATTERN, metadataText)),
                width,
                height,
                integer(firstGroup(STEPS_PATTERN, metadataText)),
                doubleValue(firstGroup(ELAPSED_PATTERN, metadataText)));
    }

    private static String firstGroup(Pattern pattern, String metadataText) {
        Matcher matcher = pattern.matcher(metadataText);

        if (!matcher.find()) {
            return null;
        }

        return StringUtils.trimToNull(matcher.group(1));
    }

    /**
     * The patterns only ever capture digits, so the sole realistic failure is a value too wide for
     * its type — a seed is a full-width {@code long}. Losing one field beats losing the image.
     */
    private static Long longValue(String captured) {
        if (captured == null) {
            return null;
        }

        try {
            return Long.parseLong(captured);
        } catch (NumberFormatException numberFormatException) {
            log.warn("Could not read '{}' as an image metadata long value", captured);

            return null;
        }
    }

    private static Integer integer(String captured) {
        if (captured == null) {
            return null;
        }

        try {
            return Integer.parseInt(captured);
        } catch (NumberFormatException numberFormatException) {
            log.warn("Could not read '{}' as an image metadata integer value", captured);

            return null;
        }
    }

    private static Double doubleValue(String captured) {
        if (captured == null) {
            return null;
        }

        try {
            return Double.parseDouble(captured);
        } catch (NumberFormatException numberFormatException) {
            log.warn("Could not read '{}' as an image metadata double value", captured);

            return null;
        }
    }
}
