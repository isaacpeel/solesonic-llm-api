package com.solesonic.model.chat;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * What the model server reported about one assistant turn, carried on the {@code RUN_FINISHED} SSE event and
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
 * {@link #cachedPromptTokens()}, {@link #promptTokensEvaluated()},
 * {@link #predictedTokensGenerated()}, {@link #draftTokens()} and {@link #draftAcceptedTokens()} are
 * summed the same way the other counts are. The four llama.cpp rate fields that sit alongside them on
 * {@link ModelCallMetadata} (per-token millis, per-second throughput) are deliberately not summed or
 * repeated here, for the same reason there is no top-level tokens-per-second: a rate from one round
 * trip does not mean anything added to another's.
 * <p>
 * The whole record is {@code null} on a message for any turn no chat model answered: an A2A agent
 * delegation, which never reaches a chat model at all, and a turn cancelled before any usage was
 * reported.
 */
public record ResponseMetadata(
        @Nullable String model,
        @Nullable String id,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Nullable Instant createdAt,
        @Nullable String finishReason,
        @Nullable Integer modelCalls,
        @Nullable Integer promptTokens,
        @Nullable Integer completionTokens,
        @Nullable Integer totalTokens,
        @Nullable Double promptMillis,
        @Nullable Double predictedMillis,
        @Nullable Double totalMillis,
        @Nullable Integer cachedPromptTokens,
        @Nullable Integer promptTokensEvaluated,
        @Nullable Integer predictedTokensGenerated,
        @Nullable Integer draftTokens,
        @Nullable Integer draftAcceptedTokens,
        @Nullable String routedModel) {

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
                sumDoubles(calls, ModelCallMetadata::predictedMillis),
                sumDoubles(calls, ResponseMetadata::callTotalMillis),
                sumIntegers(calls, ModelCallMetadata::cachedPromptTokens),
                sumIntegers(calls, ModelCallMetadata::promptTokensEvaluated),
                sumIntegers(calls, ModelCallMetadata::predictedTokensGenerated),
                sumIntegers(calls, ModelCallMetadata::draftTokens),
                sumIntegers(calls, ModelCallMetadata::draftAcceptedTokens),
                routedModel(calls));
    }

    /**
     * Prefers LiteLLM's own measured response time over the model server's self-reported
     * {@code prompt_ms}/{@code predicted_ms}: those cover only the server's own generation work, not
     * the network hop to and from it or LiteLLM's routing overhead, so they consistently undercount
     * the call's real wall-clock time. Falls back to the server's timings when the call was not
     * proxied. Null when neither source reported anything, rather than zero.
     */
    private static @Nullable Double callTotalMillis(ModelCallMetadata call) {
        LiteLlmCallMetadata liteLlm = call.liteLlm();

        if (liteLlm != null && liteLlm.responseDurationMillis() != null) {
            double overheadMillis = liteLlm.overheadDurationMillis() == null ? 0.0 : liteLlm.overheadDurationMillis();

            return liteLlm.responseDurationMillis() + overheadMillis;
        }

        if (call.promptMillis() == null && call.predictedMillis() == null) {
            return null;
        }

        return (call.promptMillis() == null ? 0.0 : call.promptMillis())
                + (call.predictedMillis() == null ? 0.0 : call.predictedMillis());
    }

    /**
     * The last call's routed model, not the first. A tool-calling turn can be routed per round trip,
     * and the call that produced the answer the user is reading is the one worth naming.
     */
    private static @Nullable String routedModel(List<ModelCallMetadata> calls) {
        String routedModel = null;

        for (ModelCallMetadata call : calls) {
            LiteLlmCallMetadata liteLlm = call.liteLlm();

            if (liteLlm != null && liteLlm.modelName() != null) {
                routedModel = liteLlm.modelName();
            }
        }

        return routedModel;
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
