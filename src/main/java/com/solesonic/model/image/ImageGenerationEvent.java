package com.solesonic.model.image;

/**
 * One frame of an image generation, as it reaches the client over SSE.
 * <p>
 * Progress and the terminal outcome travel through a single sink rather than two merged streams so
 * that ordering is a property of the type, not of a race: the {@code complete} or {@code error}
 * frame is emitted after every {@code progress} frame that preceded it, and nothing follows it.
 */
public sealed interface ImageGenerationEvent
        permits GeneratedImageSummary, ImageGenerationFailure, ImageGenerationProgress {

    String PROGRESS = "progress";
    String COMPLETE = "complete";
    String ERROR = "error";

    /**
     * The SSE event name this frame is published under. The frontend routes on it, so the three
     * values are part of the API contract.
     */
    String eventName();
}
