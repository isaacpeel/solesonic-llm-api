package com.solesonic.model.chat;

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
}
