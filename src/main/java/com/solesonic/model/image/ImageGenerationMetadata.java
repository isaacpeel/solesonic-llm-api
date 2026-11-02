package com.solesonic.model.image;

/**
 * Provenance stored with a generated image. Every field is optional: the image service returns only
 * a size, so the model is the one requested, the elapsed time is measured by this API, and the seed
 * and step count stay null.
 */
public record ImageGenerationMetadata(String model,
                                      Long seed,
                                      Integer width,
                                      Integer height,
                                      Integer steps,
                                      Double elapsedSeconds) {
}
