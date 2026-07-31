package com.solesonic.model.image;

/**
 * A progress frame, translated from the MCP {@code notifications/progress} the image tool emits.
 * <p>
 * {@code percent} is the tool's {@code progress} field read directly — it reports against a total of
 * 100 — and is monotonic, because the MCP server clamps each value to at least the previous one. It
 * is nonetheless an <em>estimate</em> between 15 and 85: the tool derives it from an expected
 * duration rather than from real per-step progress, so it can sit at 85 for a while on a slow run.
 *
 * @param percent 0-100, or null when the notification carried no total to read it against
 * @param message the tool's own user-facing text for this step, such as {@code Generating…}
 */
public record ImageGenerationProgress(Integer percent, String message) implements ImageGenerationEvent {

    @Override
    public String eventName() {
        return PROGRESS;
    }
}
