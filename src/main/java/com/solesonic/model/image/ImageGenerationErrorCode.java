package com.solesonic.model.image;

/**
 * The closed set of image generation failures the client renders copy for.
 * <p>
 * Every failure — a validation rejection from the tool, a thrown generation error, an authorization
 * denial, or a local admission-control refusal — collapses onto one of these before it leaves the
 * API. The raw detail, which can name a ComfyUI prompt id and internal host behaviour, is logged
 * server-side and never sent to the browser.
 */
public enum ImageGenerationErrorCode {

    /**
     * The prompt was empty or was rejected by the tool before any generation was attempted.
     */
    INVALID_PROMPT,

    /**
     * Generation started but did not finish inside the image server's deadline.
     */
    GENERATION_TIMEOUT,

    /**
     * The MCP server or the image backend behind it could not be reached, or failed outright.
     */
    BACKEND_UNAVAILABLE,

    /**
     * The caller's token does not carry the role the image tool requires.
     */
    FORBIDDEN,

    /**
     * Too many generations are already in flight; the caller should retry.
     */
    RATE_LIMITED,

    /**
     * Anything else. Always accompanied by a server-side log of the real cause.
     */
    INTERNAL
}
