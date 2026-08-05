package com.solesonic.model.image;

/**
 * The terminal frame of a generation that did not produce an image.
 *
 * @param code    what went wrong, from the closed set the client renders copy for
 * @param message user-safe text — never an exception string, never an internal host or prompt id
 */
public record ImageGenerationFailure(ImageGenerationErrorCode code, String message) implements ImageGenerationEvent {

    @Override
    public String eventName() {
        return ERROR;
    }
}
