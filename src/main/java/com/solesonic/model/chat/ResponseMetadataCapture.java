package com.solesonic.model.chat;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Collects the one chat response per turn that carries Ollama's own accounting.
 * <p>
 * Ollama reports counts and durations only on the response where {@code done} is true, so every
 * earlier streamed chunk is offered here and ignored. That is the whole reason this waits rather
 * than accumulating as it goes: there is nothing to accumulate, and nothing to compute — the
 * terminal response already holds the turn's totals.
 * <p>
 * Deliberately not thread-safe and deliberately not an atomic. A single subscription's
 * {@code onNext} signals are serialized by the Reactive Streams contract, so the only writer is one
 * chunk at a time, and the read in {@link #metadata()} happens after that flux has completed.
 */
public final class ResponseMetadataCapture {

    private @Nullable ResponseMetadata responseMetadata;

    public void accept(ChatResponse chatResponse) {
        Boolean done = chatResponse.getMetadata().get(ResponseMetadata.DONE);

        if (Boolean.TRUE.equals(done)) {
            responseMetadata = ResponseMetadata.from(chatResponse);
        }
    }

    /**
     * The turn's metadata, or {@code null} when Ollama never reported any — a route that calls no
     * chat model, or a turn that ended before the terminal response.
     */
    public @Nullable ResponseMetadata metadata() {
        return responseMetadata;
    }
}
