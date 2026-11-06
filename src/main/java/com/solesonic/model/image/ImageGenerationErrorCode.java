package com.solesonic.model.image;

/**
 * The closed set of image generation failures the client renders copy for.
 * <p>
 * Every failure — a rejection from the image service, a transport failure, a key that is not allowed
 * the image models, or a local admission-control refusal — collapses onto one of these before it
 * leaves the API. The raw detail, which can name internal hosts and behaviour, is logged server-side
 * and never sent to the browser.
 */
public enum ImageGenerationErrorCode {

    /**
     * The prompt was empty or was rejected before any generation was attempted.
     */
    INVALID_PROMPT("Describe the image you want before generating one."),

    /**
     * Generation started but did not finish inside the deadline.
     */
    GENERATION_TIMEOUT("Image generation is taking longer than expected. Please try again."),

    /**
     * The image service could not be reached, or failed outright.
     */
    BACKEND_UNAVAILABLE("Image generation is unavailable right now. Please try again shortly."),

    /**
     * The caller's model key is not allowed the image models.
     */
    FORBIDDEN("You do not have access to image generation."),

    /**
     * Too many generations are in flight, or the caller's key is over its budget or rate limit.
     */
    RATE_LIMITED("Too many images are being generated right now. Please try again in a moment."),

    /**
     * A requested reference image could not be used: it is not one of the caller's PNG, JPEG or
     * WebP attachments, there were more than the service accepts, no image style takes that many,
     * or the image service rejected it.
     */
    INVALID_REFERENCE_IMAGE("A reference image could not be used. Attach your own PNG, JPEG or WebP images."),

    /**
     * Anything else. Always accompanied by a server-side log of the real cause.
     */
    INTERNAL("Image generation failed unexpectedly. Please try again.");

    private final String userMessage;

    ImageGenerationErrorCode(String userMessage) {
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
