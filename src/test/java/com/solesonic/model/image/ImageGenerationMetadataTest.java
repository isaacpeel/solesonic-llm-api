package com.solesonic.model.image;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The image tool advertises no output schema, so its metadata reaches this API as prose. These
 * cases pin down that a wording change costs a null rather than a failed generation.
 */
class ImageGenerationMetadataTest {

    private static final String METADATA_TEXT = """
            Generated with FLUX.1-schnell.
            Size: 1024x1024
            Steps: 4
            Seed: 8339331079448168597
            Elapsed: 8.2s""";

    @Test
    void parseShouldReadEveryFieldFromTheDocumentedBlock() {
        ImageGenerationMetadata imageGenerationMetadata = ImageGenerationMetadata.parse(METADATA_TEXT);

        assertThat(imageGenerationMetadata.model()).isEqualTo("FLUX.1-schnell");
        assertThat(imageGenerationMetadata.width()).isEqualTo(1024);
        assertThat(imageGenerationMetadata.height()).isEqualTo(1024);
        assertThat(imageGenerationMetadata.steps()).isEqualTo(4);
        assertThat(imageGenerationMetadata.seed()).isEqualTo(8339331079448168597L);
        assertThat(imageGenerationMetadata.elapsedSeconds()).isEqualTo(8.2d);
    }

    /**
     * A seed occupies the full width of a long. Reading it as an int would silently corrupt the
     * only field that identifies one image among many from the same prompt.
     */
    @Test
    void parseShouldReadAFullWidthSeed() {
        ImageGenerationMetadata imageGenerationMetadata =
                ImageGenerationMetadata.parse("Seed: 9223372036854775807");

        assertThat(imageGenerationMetadata.seed()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void parseShouldAcceptTheMultiplicationSignBetweenDimensions() {
        ImageGenerationMetadata imageGenerationMetadata = ImageGenerationMetadata.parse("Size: 1024×768");

        assertThat(imageGenerationMetadata.width()).isEqualTo(1024);
        assertThat(imageGenerationMetadata.height()).isEqualTo(768);
    }

    @Test
    void parseShouldReturnNullFieldsWhenTheBlockIsUnrecognised() {
        ImageGenerationMetadata imageGenerationMetadata = ImageGenerationMetadata.parse("something else entirely");

        assertThat(imageGenerationMetadata.model()).isNull();
        assertThat(imageGenerationMetadata.seed()).isNull();
        assertThat(imageGenerationMetadata.width()).isNull();
        assertThat(imageGenerationMetadata.height()).isNull();
        assertThat(imageGenerationMetadata.steps()).isNull();
        assertThat(imageGenerationMetadata.elapsedSeconds()).isNull();
    }

    @Test
    void parseShouldReturnNullFieldsWhenThereIsNoMetadataAtAll() {
        ImageGenerationMetadata imageGenerationMetadata = ImageGenerationMetadata.parse("  ");

        assertThat(imageGenerationMetadata.seed()).isNull();
        assertThat(imageGenerationMetadata.width()).isNull();
    }

    /**
     * A seed too wide even for a long must cost that one field, not the image.
     */
    @Test
    void parseShouldDropAnUnreadableNumberRatherThanFail() {
        ImageGenerationMetadata imageGenerationMetadata =
                ImageGenerationMetadata.parse("Seed: 99999999999999999999999\nSteps: 4");

        assertThat(imageGenerationMetadata.seed()).isNull();
        assertThat(imageGenerationMetadata.steps()).isEqualTo(4);
    }
}
