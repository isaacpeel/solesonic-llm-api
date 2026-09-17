package com.solesonic.model.chat;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.support.UsageCalculator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Accumulates what the model server reports across one chat turn.
 * <p>
 * This accumulates rather than snapshotting a single "terminal" response, because under the OpenAI
 * protocol one model call answers in two halves and neither half is complete on its own:
 * <ul>
 *     <li>the chunk carrying {@code finish_reason} has the generations, the model name and the id —
 *     and, because Spring AI's {@code ChunkMerger} synthesises a zero {@code CompletionUsage} for any
 *     chunk without one, a usage of {@code (0,0,0)};</li>
 *     <li>the final usage chunk carries the real token counts and {@code choices: []}, so
 *     {@link ChatResponse#getResult()} is {@code null} on it — no finish reason, no text.</li>
 * </ul>
 * A tool-calling turn repeats that pair once per round trip, which is why the counts are summed and
 * each round trip is also kept individually as a {@link ModelCallMetadata}.
 * <p>
 * Deliberately not thread-safe and deliberately not atomic. A single subscription's {@code onNext}
 * signals are serialized by the Reactive Streams contract, so the only writer is one response at a
 * time, and the reads in {@link #metadata()} and {@link #calls()} happen after that flux has
 * completed.
 */
public final class ResponseMetadataCapture {

    /** The one key {@code OpenAiChatModel} always writes: epoch <em>seconds</em>, or 0 when absent. */
    static final String CREATED = "created";

    /**
     * llama.cpp adds this non-standard object to its final response. Spring AI does not know it, so
     * it arrives through {@code ChatCompletion._additionalProperties()} as a plain map. A model
     * server that does not send one simply leaves every timing null.
     */
    static final String TIMINGS = "timings";
    static final String CACHE_N = "cache_n";
    static final String PROMPT_N = "prompt_n";
    static final String PROMPT_MS = "prompt_ms";
    static final String PROMPT_PER_TOKEN_MS = "prompt_per_token_ms";
    static final String PROMPT_PER_SECOND = "prompt_per_second";
    static final String PREDICTED_N = "predicted_n";
    static final String PREDICTED_MS = "predicted_ms";
    static final String PREDICTED_PER_TOKEN_MS = "predicted_per_token_ms";
    static final String PREDICTED_PER_SECOND = "predicted_per_second";
    static final String DRAFT_N = "draft_n";
    static final String DRAFT_N_ACCEPTED = "draft_n_accepted";

    /**
     * What Spring AI's OpenAI model reports for a streamed chunk that carries no finish reason of
     * its own — the SDK enum's name for an absent value, not something the server said.
     */
    static final String UNKNOWN_FINISH_REASON = "_UNKNOWN";

    private final List<ModelCallMetadata> calls = new ArrayList<>();
    private final PendingCall pending = new PendingCall();

    private @Nullable String model;
    private @Nullable String id;
    private @Nullable Instant createdAt;
    private @Nullable String finishReason;

    public void accept(ChatResponse chatResponse) {
        ChatResponseMetadata chatResponseMetadata = chatResponse.getMetadata();

        String responseModel = blankToNull(chatResponseMetadata.getModel());
        String responseId = blankToNull(chatResponseMetadata.getId());
        Instant responseCreatedAt = createdAt(chatResponseMetadata);
        String responseFinishReason = finishReason(chatResponse);

        if (responseModel != null) {
            model = responseModel;
            pending.model = responseModel;
        }

        if (responseId != null) {
            id = responseId;
            pending.id = responseId;
        }

        if (responseCreatedAt != null) {
            createdAt = responseCreatedAt;
            pending.createdAt = responseCreatedAt;
        }

        if (responseFinishReason != null) {
            finishReason = responseFinishReason;
            pending.finishReason = responseFinishReason;
        }

        applyTimings(chatResponseMetadata);

        Usage usage = chatResponseMetadata.getUsage();

        //isEmpty covers both EmptyUsage and the synthesised (0,0,0) that every chunk without usage
        //of its own carries, so only a response the server actually reported on gets this far.
        if (!UsageCalculator.isEmpty(usage)) {
            pending.promptTokens = usage.getPromptTokens();
            pending.completionTokens = usage.getCompletionTokens();
            pending.totalTokens = usage.getTotalTokens();

            //Prefers the portable usage-level count over timings.cache_n, which applyTimings above
            //already set as a llama.cpp-only fallback — usage runs second, so it wins when reported.
            Long cacheReadInputTokens = usage.getCacheReadInputTokens();

            if (cacheReadInputTokens != null) {
                pending.cachedPromptTokens = Math.toIntExact(cacheReadInputTokens);
            }

            //Usage is the last thing a call reports, so this closes it. Flushing here rather than on
            //the next call's first chunk is what keeps the following round trip's finish reason from
            //being attributed to this one.
            flush();
        }
    }

    /**
     * The turn's totals, or {@code null} when nothing reported any — a route that calls no chat
     * model, or a turn that ended before the first usage arrived.
     */
    public @Nullable ResponseMetadata metadata() {
        settle();

        return ResponseMetadata.of(model, id, createdAt, finishReason, List.copyOf(calls));
    }

    /**
     * The per-call breakdown behind {@link #metadata()}, empty when nothing was reported. Persisted
     * alongside the totals but never published to clients.
     */
    public List<ModelCallMetadata> calls() {
        settle();

        return List.copyOf(calls);
    }

    /**
     * Deals with the leftover a well-behaved server never produces: timings that arrived on their own
     * after the usage chunk already closed the call. Merging them into that call rather than
     * appending keeps {@link ResponseMetadata#modelCalls()} honest — a timings-only record is not a
     * model call. Idempotent, so reading the capture twice does not double-count.
     */
    private void settle() {
        if (!pending.hasTimings()) {
            pending.reset();

            return;
        }

        int lastIndex = calls.size() - 1;

        if (lastIndex >= 0) {
            ModelCallMetadata lastCall = calls.get(lastIndex);

            if (lastCall.promptMillis() == null && lastCall.predictedMillis() == null) {
                calls.set(lastIndex, new ModelCallMetadata(
                        lastCall.model(),
                        lastCall.id(),
                        lastCall.createdAt(),
                        lastCall.finishReason(),
                        lastCall.promptTokens(),
                        lastCall.completionTokens(),
                        lastCall.totalTokens(),
                        pending.promptMillis,
                        pending.predictedMillis,
                        pending.predictedPerSecond,
                        pending.cachedPromptTokens,
                        pending.promptTokensEvaluated,
                        pending.promptPerTokenMillis,
                        pending.promptPerSecond,
                        pending.predictedTokensGenerated,
                        pending.predictedPerTokenMillis,
                        pending.draftTokens,
                        pending.draftAcceptedTokens,
                        lastCall.liteLlm()));

                pending.reset();

                return;
            }
        }

        flush();
    }

    private void flush() {
        calls.add(new ModelCallMetadata(
                pending.model,
                pending.id,
                pending.createdAt,
                pending.finishReason,
                pending.promptTokens,
                pending.completionTokens,
                pending.totalTokens,
                pending.promptMillis,
                pending.predictedMillis,
                pending.predictedPerSecond,
                pending.cachedPromptTokens,
                pending.promptTokensEvaluated,
                pending.promptPerTokenMillis,
                pending.promptPerSecond,
                pending.predictedTokensGenerated,
                pending.predictedPerTokenMillis,
                pending.draftTokens,
                pending.draftAcceptedTokens,
                null));

        pending.reset();
    }

    /**
     * Attaches what the proxy reported about each HTTP call, paired positionally: both lists are in
     * the order the round trips were made, so the nth of each is the same call.
     * <p>
     * Pairing by position rather than by an id is forced — the proxy's {@code x-litellm-call-id} is
     * in the headers and the model's completion id is in the body, and no field appears in both. The
     * shorter list governs, so a round trip that reported one and not the other simply keeps what it
     * has rather than shifting every later pairing by one.
     */
    public void applyLiteLlmCalls(List<LiteLlmCallMetadata> liteLlmCalls) {
        settle();

        int pairedCalls = Math.min(calls.size(), liteLlmCalls.size());

        for (int index = 0; index < pairedCalls; index++) {
            calls.set(index, calls.get(index).withLiteLlm(liteLlmCalls.get(index)));
        }
    }

    private void applyTimings(ChatResponseMetadata chatResponseMetadata) {
        Object timings = chatResponseMetadata.get(TIMINGS);

        //Everything reached through _additionalProperties has been converted to plain Java objects,
        //so the numbers can arrive as Integer, Long or Double. Narrow, never cast.
        if (!(timings instanceof Map<?, ?> timingsMap)) {
            return;
        }

        Double promptMillis = millis(timingsMap, PROMPT_MS);
        Double predictedMillis = millis(timingsMap, PREDICTED_MS);
        Double predictedPerSecond = millis(timingsMap, PREDICTED_PER_SECOND);
        Integer cacheN = count(timingsMap, CACHE_N);
        Integer promptN = count(timingsMap, PROMPT_N);
        Double promptPerTokenMillis = millis(timingsMap, PROMPT_PER_TOKEN_MS);
        Double promptPerSecond = millis(timingsMap, PROMPT_PER_SECOND);
        Integer predictedN = count(timingsMap, PREDICTED_N);
        Double predictedPerTokenMillis = millis(timingsMap, PREDICTED_PER_TOKEN_MS);
        Integer draftN = count(timingsMap, DRAFT_N);
        Integer draftNAccepted = count(timingsMap, DRAFT_N_ACCEPTED);

        if (promptMillis != null) {
            pending.promptMillis = promptMillis;
        }

        if (predictedMillis != null) {
            pending.predictedMillis = predictedMillis;
        }

        if (predictedPerSecond != null) {
            pending.predictedPerSecond = predictedPerSecond;
        }

        //llama.cpp-only fallback: accept()'s usage handling overwrites this with the portable
        //usage.prompt_tokens_details.cached_tokens count whenever the response reports one.
        if (cacheN != null) {
            pending.cachedPromptTokens = cacheN;
        }

        if (promptN != null) {
            pending.promptTokensEvaluated = promptN;
        }

        if (promptPerTokenMillis != null) {
            pending.promptPerTokenMillis = promptPerTokenMillis;
        }

        if (promptPerSecond != null) {
            pending.promptPerSecond = promptPerSecond;
        }

        if (predictedN != null) {
            pending.predictedTokensGenerated = predictedN;
        }

        if (predictedPerTokenMillis != null) {
            pending.predictedPerTokenMillis = predictedPerTokenMillis;
        }

        if (draftN != null) {
            pending.draftTokens = draftN;
        }

        if (draftNAccepted != null) {
            pending.draftAcceptedTokens = draftNAccepted;
        }
    }

    private static @Nullable Double millis(Map<?, ?> timings, String key) {
        Object value = timings.get(key);

        if (value instanceof Number number) {
            return number.doubleValue();
        }

        return null;
    }

    private static @Nullable Integer count(Map<?, ?> timings, String key) {
        Object value = timings.get(key);

        if (value instanceof Number number) {
            return number.intValue();
        }

        return null;
    }

    /**
     * {@code created} is epoch seconds. Spring AI substitutes 0 for a server that omits the field, so
     * that is "not reported" rather than 1970.
     */
    private static @Nullable Instant createdAt(ChatResponseMetadata chatResponseMetadata) {
        Object created = chatResponseMetadata.get(CREATED);

        if (created instanceof Number number && number.longValue() > 0L) {
            return Instant.ofEpochSecond(number.longValue());
        }

        return null;
    }

    /**
     * Spring AI reports the OpenAI SDK enum's own name, so the value is upper case. Lower-cased here
     * so the published field keeps the {@code stop} / {@code tool_calls} form the protocol itself
     * uses, and the placeholder for "this chunk had none" is discarded.
     */
    private static @Nullable String finishReason(ChatResponse chatResponse) {
        String reason = Optional.ofNullable(chatResponse.getResult())
                .map(Generation::getMetadata)
                .map(ChatGenerationMetadata::getFinishReason)
                .orElse(null);

        if (reason == null || reason.isBlank() || UNKNOWN_FINISH_REASON.equals(reason)) {
            return null;
        }

        return reason.toLowerCase(Locale.ROOT);
    }

    /**
     * {@link ChatResponseMetadata} defaults both the model and the id to an empty string rather than
     * null, so blank has to be read as "not reported" or it would overwrite a real value.
     */
    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value;
    }

    /**
     * The call currently being assembled. Mutable and reused rather than rebuilt, because a turn
     * fills it from several responses before any one of them closes it.
     */
    private static final class PendingCall {

        private @Nullable String model;
        private @Nullable String id;
        private @Nullable Instant createdAt;
        private @Nullable String finishReason;
        private @Nullable Integer promptTokens;
        private @Nullable Integer completionTokens;
        private @Nullable Integer totalTokens;
        private @Nullable Double promptMillis;
        private @Nullable Double predictedMillis;
        private @Nullable Double predictedPerSecond;
        private @Nullable Integer cachedPromptTokens;
        private @Nullable Integer promptTokensEvaluated;
        private @Nullable Double promptPerTokenMillis;
        private @Nullable Double promptPerSecond;
        private @Nullable Integer predictedTokensGenerated;
        private @Nullable Double predictedPerTokenMillis;
        private @Nullable Integer draftTokens;
        private @Nullable Integer draftAcceptedTokens;

        private boolean hasTimings() {
            return promptMillis != null || predictedMillis != null || predictedPerSecond != null
                    || cachedPromptTokens != null || promptTokensEvaluated != null
                    || promptPerTokenMillis != null || promptPerSecond != null
                    || predictedTokensGenerated != null || predictedPerTokenMillis != null
                    || draftTokens != null || draftAcceptedTokens != null;
        }

        private void reset() {
            model = null;
            id = null;
            createdAt = null;
            finishReason = null;
            promptTokens = null;
            completionTokens = null;
            totalTokens = null;
            promptMillis = null;
            predictedMillis = null;
            predictedPerSecond = null;
            cachedPromptTokens = null;
            promptTokensEvaluated = null;
            promptPerTokenMillis = null;
            promptPerSecond = null;
            predictedTokensGenerated = null;
            predictedPerTokenMillis = null;
            draftTokens = null;
            draftAcceptedTokens = null;
        }
    }
}
