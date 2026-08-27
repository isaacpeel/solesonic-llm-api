package com.solesonic.model.chat;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * What the model server reported about one model call.
 * <p>
 * A chat turn is not always a single call: a tool-calling turn runs the model again after every tool
 * result, and each of those round trips reports its own token counts. {@link ResponseMetadata} sums
 * them into the turn's total, which is what a client is given; this record is the breakdown behind
 * that total, persisted alongside it on {@code chat_message.response_metadata_calls} so a turn's cost
 * can be attributed later.
 * <p>
 * Every field is the server's own accounting, copied verbatim — nothing here is measured or derived
 * by this application. The three timing fields come from llama.cpp's non-standard {@code timings}
 * object and are null against a model server that does not send one.
 */
public record ModelCallMetadata(
        @Nullable String model,
        @Nullable String id,
        //Pinned to a string so it goes back out in ISO-8601 form rather than the numeric timestamp a
        //mapper left to its own defaults could choose.
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Nullable Instant createdAt,
        @Nullable String finishReason,
        @Nullable Integer promptTokens,
        @Nullable Integer completionTokens,
        @Nullable Integer totalTokens,
        @Nullable Double promptMillis,
        @Nullable Double predictedMillis,
        @Nullable Double predictedPerSecond) {
}
