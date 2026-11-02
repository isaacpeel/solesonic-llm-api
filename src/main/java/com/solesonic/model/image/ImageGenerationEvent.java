package com.solesonic.model.image;

/**
 * The terminal frame of an image generation, as it reaches the client over SSE: {@code complete} with
 * the image, or {@code error}. The image service reports no progress, so nothing precedes it.
 */
public sealed interface ImageGenerationEvent permits GeneratedImageSummary, ImageGenerationFailure {

    String COMPLETE = "complete";
    String ERROR = "error";

    /**
     * The SSE event name this frame is published under. The frontend routes on it, so the values are
     * part of the API contract.
     */
    String eventName();
}
