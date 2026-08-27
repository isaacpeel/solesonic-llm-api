package com.solesonic.model.chat;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * What the model server reported about one assistant turn, carried on the {@code done} SSE event and
 * persisted on the message row.
 * <p>
 * Every field is the server's own accounting, copied verbatim — nothing here is measured or derived
 * by this application, so a client reading it sees the model server's numbers rather than an
 * approximation of them. The one qualification is that the counts and durations are <em>sums</em>: a
 * tool-calling turn runs the model once per tool result and each round trip reports separately, so
 * these are the turn's totals and {@link #modelCalls()} says how many calls went into them. The
 * per-call breakdown is persisted next to this on {@code chat_message.response_metadata_calls} as
 * {@link ModelCallMetadata}, and is deliberately not published to clients.
 * <p>
 * {@link #promptMillis()} and {@link #predictedMillis()} come from llama.cpp's non-standard
 * {@code timings} object, which Spring AI passes through as an unrecognised top-level property. A
 * model server that does not send one leaves them null; the token counts are the portable part.
 * <p>
 * The whole record is {@code null} on a message for any turn no chat model answered: an A2A agent
 * delegation, which never reaches a chat model at all, and a turn cancelled before any usage was
 * reported.
 */
public record ResponseMetadata(
        @Nullable String model,
        @Nullable String id,
        //Pinned to a string so it goes back out in ISO-8601 form rather than the numeric timestamp a
        //mapper left to its own defaults could choose.
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Nullable Instant createdAt,
        @Nullable String finishReason,
        //Nullable like everything else, because a row persisted under an older shape has no count to
        //read back and a primitive would fail the whole record rather than come back unknown.
        @Nullable Integer modelCalls,
        @Nullable Integer promptTokens,
        @Nullable Integer completionTokens,
        @Nullable Integer totalTokens,
        @Nullable Double promptMillis,
        @Nullable Double predictedMillis) {

    /**
     * Folds one turn's calls into its totals, or returns {@code null} when the turn reported none.
     * <p>
     * There is deliberately no top-level tokens-per-second: a single rate means nothing across
     * several round trips, and this record does not compute what the server did not report. It stays
     * on {@link ModelCallMetadata}, and a client wanting one for the turn divides
     * {@code completionTokens} by {@code predictedMillis / 1000}.
     */
    public static @Nullable ResponseMetadata of(@Nullable String model,
                                                @Nullable String id,
                                                @Nullable Instant createdAt,
                                                @Nullable String finishReason,
                                                List<ModelCallMetadata> calls) {
        if (calls.isEmpty()) {
            return null;
        }

        return new ResponseMetadata(
                model,
                id,
                createdAt,
                finishReason,
                calls.size(),
                sumIntegers(calls, ModelCallMetadata::promptTokens),
                sumIntegers(calls, ModelCallMetadata::completionTokens),
                sumIntegers(calls, ModelCallMetadata::totalTokens),
                sumDoubles(calls, ModelCallMetadata::promptMillis),
                sumDoubles(calls, ModelCallMetadata::predictedMillis));
    }

    /**
     * Null when no call reported the field at all, rather than a zero — a zero would read as "the
     * model used no tokens" when what happened is that the server never said.
     */
    private static @Nullable Integer sumIntegers(List<ModelCallMetadata> calls,
                                                 Function<ModelCallMetadata, @Nullable Integer> field) {
        Integer total = null;

        for (ModelCallMetadata call : calls) {
            Integer value = field.apply(call);

            if (value != null) {
                total = total == null ? value : total + value;
            }
        }

        return total;
    }

    private static @Nullable Double sumDoubles(List<ModelCallMetadata> calls,
                                               Function<ModelCallMetadata, @Nullable Double> field) {
        Double total = null;

        for (ModelCallMetadata call : calls) {
            Double value = field.apply(call);

            if (value != null) {
                total = total == null ? value : total + value;
            }
        }

        return total;
    }
}
