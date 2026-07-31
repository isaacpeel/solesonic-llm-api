package com.solesonic.exception.image;

import com.solesonic.model.image.ImageGenerationErrorCode;

/**
 * A generation that failed, already mapped onto the code and user-safe text the client will see.
 * <p>
 * The exception message is what reaches the browser, so it must stay free of exception strings,
 * internal hosts, and ComfyUI prompt ids. The real cause travels as the {@code cause} and is logged
 * where the failure is classified.
 */
public class ImageGenerationException extends RuntimeException {

    private final ImageGenerationErrorCode errorCode;

    public ImageGenerationException(ImageGenerationErrorCode errorCode, String userSafeMessage) {
        super(userSafeMessage);
        this.errorCode = errorCode;
    }

    public ImageGenerationException(ImageGenerationErrorCode errorCode, String userSafeMessage, Throwable cause) {
        super(userSafeMessage, cause);
        this.errorCode = errorCode;
    }

    public ImageGenerationErrorCode getErrorCode() {
        return errorCode;
    }
}
