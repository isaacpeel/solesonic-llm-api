package com.solesonic.model.chat.attachment;

/**
 * Why an image attachment was not described by the vision model.
 * <p>
 * A closed set on purpose: the frontend maps each constant to its own copy, so adding a constant is
 * an API change that needs a matching change there. Serialized by name, both on the
 * {@code attachment} SSE frame and on {@link ChatAttachmentSummary} in chat history.
 */
public enum VisionFailureReason {
    /**
     * The vision model was reachable but did not answer in time — most often a cold model load.
     */
    VISION_TIMEOUT,

    /**
     * The vision host could not be reached, or answered with an error.
     */
    VISION_UNAVAILABLE,

    /**
     * The image is larger than {@code solesonic.llm.vision.max-image-bytes}.
     */
    IMAGE_TOO_LARGE,

    /**
     * The vision model returned nothing usable for this image, or the attachment could not be
     * loaded at all.
     */
    IMAGE_UNREADABLE,

    /**
     * More images were attached to one message than the vision pass will describe.
     */
    EXCEEDED_IMAGE_LIMIT
}
