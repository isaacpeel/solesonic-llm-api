package com.solesonic.model.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jspecify.annotations.Nullable;

/**
 * What the LiteLLM proxy reported about one HTTP call, taken from its {@code x-litellm-*} response
 * headers rather than from the response body.
 * <p>
 * None of this is in the body. A proxied completion is a plain OpenAI payload, so the routing
 * decision is visible only here: {@link #modelName()} is the model that actually ran, where
 * {@link ModelCallMetadata#model()} is the group that was asked for — {@code auto-model} on every
 * turn, which is the same "what was requested, not what ran" gap {@link ResponseMetadata} exists to
 * close.
 * <p>
 * The cost headers are deliberately not captured. They report {@code 0.0} until per-token pricing is
 * configured for the model in LiteLLM, and a persisted zero reads as "this turn was free" rather
 * than "nobody priced it". {@link #callId()} is the join key to LiteLLM's own spend logs, which is
 * where cost can be answered properly once it exists.
 * <p>
 * {@link #responseDurationMillis()} and {@link #overheadDurationMillis()} are the proxy's own
 * measured wall-clock time for the call, and are what {@link ResponseMetadata#totalMillis()} prefers
 * over the model server's self-reported {@code prompt_ms}/{@code predicted_ms}: llama.cpp's timings
 * cover only its own generation work, not the network hop to and from it or LiteLLM's routing, so
 * they consistently undercount what the call actually took.
 */
public record LiteLlmCallMetadata(
        @Nullable String callId,
        @Nullable String modelName,
        @Nullable String modelApiBase,
        @Nullable Integer attemptedRetries,
        @Nullable Integer attemptedFallbacks,
        @Nullable Double responseDurationMillis,
        @Nullable Double overheadDurationMillis) {

    /**
     * Whether the proxy said anything at all. A response from a server that is not LiteLLM carries
     * none of these headers, and recording an all-null record would claim a proxy call that never
     * happened.
     * <p>
     * Deliberately not named {@code isPresent}. Hibernate persists this record to jsonb through a
     * Jackson mapper of its own, which treats a no-argument {@code isX()} as a getter for a property
     * {@code x} — it wrote a {@code "present"} field that no record component could read back, and
     * that mapper, unlike the application's, fails on unknown properties. The {@link JsonIgnore} is
     * the belt to this rename's braces.
     */
    @JsonIgnore
    public boolean hasAnyValue() {
        return callId != null
                || modelName != null
                || modelApiBase != null
                || attemptedRetries != null
                || attemptedFallbacks != null
                || responseDurationMillis != null
                || overheadDurationMillis != null;
    }
}
