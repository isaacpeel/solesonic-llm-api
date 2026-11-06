package com.solesonic.model.chat;

import com.openai.core.JsonValue;
import com.openai.models.completions.CompletionUsage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fixtures here mirror the shape Spring AI's {@code OpenAiChatModel} actually produces while
 * streaming, which is the whole reason the capture accumulates: a text chunk carries the model name
 * and a synthesised {@code (0,0,0)} usage, the chunk with the finish reason carries no counts either,
 * and the counts arrive last on a chunk with no generations at all.
 */
class ResponseMetadataCaptureTest {

    private static final long CREATED_EPOCH_SECONDS = 1787858565L;
    private static final Instant CREATED_AT = Instant.ofEpochSecond(CREATED_EPOCH_SECONDS);

    private static ChatResponseMetadata.Builder baseMetadata() {
        return ChatResponseMetadata.builder()
                .model("qwen3-8b")
                .id("chatcmpl-1")
                .keyValue(ResponseMetadataCapture.CREATED, CREATED_EPOCH_SECONDS);
    }

    /** A chunk of text. Spring AI stamps an absent finish reason as the SDK enum's own placeholder. */
    private static ChatResponse textChunk(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text),
                        ChatGenerationMetadata.builder().finishReason(ResponseMetadataCapture.UNKNOWN_FINISH_REASON).build())),
                baseMetadata().usage(new DefaultUsage(0, 0, 0)).build());
    }

    /** The chunk that closes the generation: it has the finish reason, and still no counts. */
    private static ChatResponse finishReasonChunk(String finishReason) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(""),
                        ChatGenerationMetadata.builder().finishReason(finishReason).build())),
                baseMetadata().usage(new DefaultUsage(0, 0, 0)).build());
    }

    /** OpenAI's final usage chunk: {@code choices: []}, so this response has no result at all. */
    private static ChatResponse usageChunk(int promptTokens, int completionTokens, boolean withTimings) {
        ChatResponseMetadata.Builder metadata = baseMetadata()
                .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens));

        if (withTimings) {
            metadata.keyValue(ResponseMetadataCapture.TIMINGS, Map.of(
                    "prompt_n", 1042,
                    ResponseMetadataCapture.PROMPT_MS, 130.079,
                    "predicted_n", 259,
                    ResponseMetadataCapture.PREDICTED_MS, 4232.71,
                    ResponseMetadataCapture.PREDICTED_PER_SECOND, 61.2));
        }

        return new ChatResponse(List.of(), metadata.build());
    }

    /**
     * The usage chunk as it arrives through LiteLLM: {@code OpenAiChatModel.getDefaultUsage} hands the
     * SDK's whole {@link CompletionUsage} to {@link DefaultUsage} as its native usage, and the proxy's
     * {@code tokens_per_second} rides on it as an unrecognised property.
     */
    private static ChatResponse proxiedUsageChunk(int promptTokens, int completionTokens, @Nullable JsonValue tokensPerSecond) {
        CompletionUsage.Builder completionUsage = CompletionUsage.builder()
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(promptTokens + completionTokens);

        if (tokensPerSecond != null) {
            completionUsage.putAdditionalProperty(ResponseMetadataCapture.TOKENS_PER_SECOND, tokensPerSecond);
        }

        return new ChatResponse(List.of(), baseMetadata()
                .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens,
                        completionUsage.build(), null, null))
                .build());
    }

    @Test
    void capturesNothingWhileOnlyTextHasArrived() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        responseMetadataCapture.accept(textChunk("hel"));
        responseMetadataCapture.accept(textChunk("lo"));

        assertThat(responseMetadataCapture.metadata()).isNull();
        assertThat(responseMetadataCapture.calls()).isEmpty();
    }

    /**
     * The turn's answer is split across two responses — the finish reason on one, the counts on the
     * next, whose {@code getResult()} is null. Both halves have to end up on the same call.
     */
    @Test
    void joinsTheFinishReasonAndTheCountsFromTheirSeparateChunks() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        responseMetadataCapture.accept(textChunk("hello"));
        responseMetadataCapture.accept(finishReasonChunk("STOP"));
        responseMetadataCapture.accept(usageChunk(1042, 259, true));

        ResponseMetadata responseMetadata = responseMetadataCapture.metadata();

        assertThat(responseMetadata).isNotNull();
        assertThat(responseMetadata.model()).isEqualTo("qwen3-8b");
        assertThat(responseMetadata.id()).isEqualTo("chatcmpl-1");
        assertThat(responseMetadata.createdAt()).isEqualTo(CREATED_AT);
        assertThat(responseMetadata.finishReason()).isEqualTo("stop");
        assertThat(responseMetadata.modelCalls()).isEqualTo(1);
        assertThat(responseMetadata.promptTokens()).isEqualTo(1042);
        assertThat(responseMetadata.completionTokens()).isEqualTo(259);
        assertThat(responseMetadata.totalTokens()).isEqualTo(1301);
        assertThat(responseMetadata.promptMillis()).isEqualTo(130.079);
        assertThat(responseMetadata.predictedMillis()).isEqualTo(4232.71);
        assertThat(responseMetadata.totalMillis()).isEqualTo(4362.789);

        assertThat(responseMetadataCapture.calls()).singleElement()
                .satisfies(call -> {
                    assertThat(call.finishReason()).isEqualTo("stop");
                    assertThat(call.promptTokens()).isEqualTo(1042);
                    assertThat(call.predictedPerSecond()).isEqualTo(61.2);
                });
    }

    /**
     * A tool-calling turn runs the model again after the tool result. Each round trip must become its
     * own call, and the round trips' finish reasons must not bleed into each other — the earlier one
     * ends in {@code tool_calls}, and only the last one is the turn's.
     */
    @Test
    void sumsEveryRoundTripOfAToolCallingTurn() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        responseMetadataCapture.accept(finishReasonChunk("TOOL_CALLS"));
        responseMetadataCapture.accept(usageChunk(1042, 88, false));

        responseMetadataCapture.accept(textChunk("the answer"));
        responseMetadataCapture.accept(finishReasonChunk("STOP"));
        responseMetadataCapture.accept(usageChunk(1380, 165, false));

        ResponseMetadata responseMetadata = responseMetadataCapture.metadata();

        assertThat(responseMetadata).isNotNull();
        assertThat(responseMetadata.modelCalls()).isEqualTo(2);
        assertThat(responseMetadata.promptTokens()).isEqualTo(2422);
        assertThat(responseMetadata.completionTokens()).isEqualTo(253);
        assertThat(responseMetadata.totalTokens()).isEqualTo(2675);
        assertThat(responseMetadata.finishReason()).isEqualTo("stop");

        assertThat(responseMetadataCapture.calls())
                .extracting(ModelCallMetadata::finishReason)
                .containsExactly("tool_calls", "stop");
    }

    /**
     * A model server that is not llama.cpp sends no timings object. The counts still have to land, and
     * the durations have to stay null rather than becoming zero.
     */
    @Test
    void toleratesAServerThatReportsNoTimings() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        responseMetadataCapture.accept(finishReasonChunk("STOP"));
        responseMetadataCapture.accept(usageChunk(10, 2, false));

        ResponseMetadata responseMetadata = responseMetadataCapture.metadata();

        assertThat(responseMetadata).isNotNull();
        assertThat(responseMetadata.totalTokens()).isEqualTo(12);
        assertThat(responseMetadata.promptMillis()).isNull();
        assertThat(responseMetadata.predictedMillis()).isNull();
        assertThat(responseMetadata.totalMillis()).isNull();
    }

    /**
     * Reading the capture is not a one-shot: the done event reads the totals and the persistence call
     * reads the breakdown, so neither read may flush anything a second time.
     */
    @Test
    void readingTheCaptureTwiceDoesNotDoubleCount() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        responseMetadataCapture.accept(finishReasonChunk("STOP"));
        responseMetadataCapture.accept(usageChunk(1042, 259, true));

        assertThat(responseMetadataCapture.metadata()).isNotNull();
        assertThat(responseMetadataCapture.calls()).hasSize(1);

        ResponseMetadata second = responseMetadataCapture.metadata();

        assertThat(second).isNotNull();
        assertThat(second.modelCalls()).isEqualTo(1);
        assertThat(second.promptTokens()).isEqualTo(1042);
        assertThat(responseMetadataCapture.calls()).hasSize(1);
    }

    /**
     * The blocking tool route hands over a single response carrying both halves at once. Spring AI has
     * already accumulated usage across its own tool loop by then, so this is one call, not several.
     */
    @Test
    void capturesASingleBlockingResponseAsOneCall() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        responseMetadataCapture.accept(new ChatResponse(
                List.of(new Generation(new AssistantMessage("tool result"),
                        ChatGenerationMetadata.builder().finishReason("STOP").build())),
                baseMetadata().usage(new DefaultUsage(300, 40, 340)).build()));

        ResponseMetadata responseMetadata = responseMetadataCapture.metadata();

        assertThat(responseMetadata).isNotNull();
        assertThat(responseMetadata.modelCalls()).isEqualTo(1);
        assertThat(responseMetadata.totalTokens()).isEqualTo(340);
        assertThat(responseMetadata.finishReason()).isEqualTo("stop");
    }

    /**
     * Pins the exact shape of a real llama.cpp blocking response (saved under
     * {@code .idea/httpRequests/2026-09-16T181644.200.json}): a single response whose {@code usage}
     * carries {@code prompt_tokens_details.cached_tokens} and whose {@code timings} carries every
     * field, including the speculative-decoding {@code draft_n}/{@code draft_n_accepted} pair this
     * response was the first evidence of anywhere in the codebase.
     */
    @Test
    void capturesEveryLlamaCppTimingsFieldAndTheCacheReadCountFromARealResponse() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        ChatResponseMetadata chatResponseMetadata = baseMetadata()
                .usage(new DefaultUsage(11, 227, 238, null, 7L, null))
                .keyValue(ResponseMetadataCapture.TIMINGS, Map.ofEntries(
                        Map.entry(ResponseMetadataCapture.CACHE_N, 7),
                        Map.entry(ResponseMetadataCapture.PROMPT_N, 4),
                        Map.entry(ResponseMetadataCapture.PROMPT_MS, 49.823),
                        Map.entry(ResponseMetadataCapture.PROMPT_PER_TOKEN_MS, 12.45575),
                        Map.entry(ResponseMetadataCapture.PROMPT_PER_SECOND, 80.28420608955703),
                        Map.entry(ResponseMetadataCapture.PREDICTED_N, 227),
                        Map.entry(ResponseMetadataCapture.PREDICTED_MS, 1282.429),
                        Map.entry(ResponseMetadataCapture.PREDICTED_PER_TOKEN_MS, 5.674464601769912),
                        Map.entry(ResponseMetadataCapture.PREDICTED_PER_SECOND, 176.22807968316374),
                        Map.entry(ResponseMetadataCapture.DRAFT_N, 192),
                        Map.entry(ResponseMetadataCapture.DRAFT_N_ACCEPTED, 164)))
                .build();

        responseMetadataCapture.accept(new ChatResponse(
                List.of(new Generation(new AssistantMessage("Hello! How can I help you today?"),
                        ChatGenerationMetadata.builder().finishReason("STOP").build())),
                chatResponseMetadata));

        ResponseMetadata responseMetadata = responseMetadataCapture.metadata();

        assertThat(responseMetadata).isNotNull();
        assertThat(responseMetadata.promptTokens()).isEqualTo(11);
        assertThat(responseMetadata.completionTokens()).isEqualTo(227);
        assertThat(responseMetadata.cachedPromptTokens()).isEqualTo(7);
        assertThat(responseMetadata.promptTokensEvaluated()).isEqualTo(4);
        assertThat(responseMetadata.predictedTokensGenerated()).isEqualTo(227);
        assertThat(responseMetadata.draftTokens()).isEqualTo(192);
        assertThat(responseMetadata.draftAcceptedTokens()).isEqualTo(164);

        assertThat(responseMetadataCapture.calls()).singleElement()
                .satisfies(call -> {
                    assertThat(call.cachedPromptTokens()).isEqualTo(7);
                    assertThat(call.promptTokensEvaluated()).isEqualTo(4);
                    assertThat(call.promptPerTokenMillis()).isEqualTo(12.45575);
                    assertThat(call.promptPerSecond()).isEqualTo(80.28420608955703);
                    assertThat(call.predictedTokensGenerated()).isEqualTo(227);
                    assertThat(call.predictedPerTokenMillis()).isEqualTo(5.674464601769912);
                    assertThat(call.draftTokens()).isEqualTo(192);
                    assertThat(call.draftAcceptedTokens()).isEqualTo(164);
                });
    }

    /**
     * {@code cachedPromptTokens} prefers the portable {@code usage.prompt_tokens_details.cached_tokens}
     * over llama.cpp's own {@code timings.cache_n} whenever both are reported and disagree — usage is
     * read after timings within {@code accept()}, so it wins.
     */
    @Test
    void prefersUsagesCacheReadCountOverTimingsCacheNWhenBothAreReported() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        ChatResponseMetadata chatResponseMetadata = baseMetadata()
                .usage(new DefaultUsage(11, 227, 238, null, 9L, null))
                .keyValue(ResponseMetadataCapture.TIMINGS, Map.of(ResponseMetadataCapture.CACHE_N, 7))
                .build();

        responseMetadataCapture.accept(new ChatResponse(
                List.of(new Generation(new AssistantMessage("hi"),
                        ChatGenerationMetadata.builder().finishReason("STOP").build())),
                chatResponseMetadata));

        assertThat(responseMetadataCapture.metadata()).isNotNull()
                .satisfies(metadata -> assertThat(metadata.cachedPromptTokens()).isEqualTo(9));
    }

    @Test
    void capturesTheProxysTokensPerSecondFromTheUsageChunk() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        responseMetadataCapture.accept(finishReasonChunk("STOP"));
        responseMetadataCapture.accept(proxiedUsageChunk(14, 10, JsonValue.from(222.23)));

        assertThat(responseMetadataCapture.calls()).singleElement()
                .satisfies(call -> assertThat(call.tokensPerSecond()).isEqualTo(222.23));
        assertThat(responseMetadataCapture.metadata()).isNotNull()
                .satisfies(metadata -> assertThat(metadata.tokensPerSecond()).isEqualTo(222.23));
    }

    @Test
    void leavesTokensPerSecondNullWhenTheUsageCarriesNone() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        responseMetadataCapture.accept(finishReasonChunk("STOP"));
        responseMetadataCapture.accept(proxiedUsageChunk(14, 10, null));

        assertThat(responseMetadataCapture.calls()).singleElement()
                .satisfies(call -> assertThat(call.tokensPerSecond()).isNull());
    }

    @Test
    void ignoresANonNumericTokensPerSecond() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        responseMetadataCapture.accept(finishReasonChunk("STOP"));
        responseMetadataCapture.accept(proxiedUsageChunk(14, 10, JsonValue.from("fast")));

        assertThat(responseMetadataCapture.calls()).singleElement()
                .satisfies(call -> {
                    assertThat(call.tokensPerSecond()).isNull();
                    assertThat(call.totalTokens()).isEqualTo(24);
                });
    }

    @Test
    void keepsEachRoundTripsTokensPerSecondInOrder() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        responseMetadataCapture.accept(finishReasonChunk("TOOL_CALLS"));
        responseMetadataCapture.accept(proxiedUsageChunk(1042, 88, JsonValue.from(180.5)));

        responseMetadataCapture.accept(finishReasonChunk("STOP"));
        responseMetadataCapture.accept(proxiedUsageChunk(1380, 165, JsonValue.from(222.23)));

        assertThat(responseMetadataCapture.calls())
                .extracting(ModelCallMetadata::tokensPerSecond)
                .containsExactly(180.5, 222.23);
    }

    /**
     * Timings arriving on their own after the usage chunk closed the call are merged into it, and
     * the merge must not drop the rate the usage chunk already recorded.
     */
    @Test
    void keepsTokensPerSecondWhenLateTimingsAreMergedIntoTheCall() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        responseMetadataCapture.accept(finishReasonChunk("STOP"));
        responseMetadataCapture.accept(proxiedUsageChunk(14, 10, JsonValue.from(222.23)));
        responseMetadataCapture.accept(new ChatResponse(List.of(), baseMetadata()
                .usage(new DefaultUsage(0, 0, 0))
                .keyValue(ResponseMetadataCapture.TIMINGS, Map.of(ResponseMetadataCapture.PREDICTED_MS, 45.0))
                .build()));

        assertThat(responseMetadataCapture.calls()).singleElement()
                .satisfies(call -> {
                    assertThat(call.predictedMillis()).isEqualTo(45.0);
                    assertThat(call.tokensPerSecond()).isEqualTo(222.23);
                });
    }
}
