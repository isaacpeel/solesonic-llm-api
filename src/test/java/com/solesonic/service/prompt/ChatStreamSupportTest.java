package com.solesonic.service.prompt;

import com.solesonic.model.chat.ModelCallMetadata;
import com.solesonic.model.chat.ResponseMetadata;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.litellm.LiteLlmHeaderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Pins the one thing {@code capturingContentFlux} adds over {@code contentFlux}: the turn's own
 * accounting reaches the message row exactly once, and only for a turn that actually finished.
 * <p>
 * The fixtures mirror what Spring AI's OpenAI model produces while streaming — a text chunk with a
 * synthesised {@code (0,0,0)} usage, a finish-reason chunk with no counts either, and the counts
 * last on a chunk with no generations at all.
 */
@ExtendWith(MockitoExtension.class)
class ChatStreamSupportTest {

    /**
     * Deliberately not a model name a caller would have requested: this field exists to record what
     * answered, which a router or fallback group can make a different thing entirely.
     */
    private static final String SERVER_REPORTED_MODEL = "qwen3-8b-instruct-q6";

    @Mock
    private ChatMessageService chatMessageService;

    private UUID chatId;
    private ZonedDateTime since;

    @BeforeEach
    void setUp() {
        chatId = UUID.randomUUID();
        since = ZonedDateTime.now();
    }

    private static ChatResponseMetadata.Builder baseMetadata() {
        return ChatResponseMetadata.builder()
                .model(SERVER_REPORTED_MODEL)
                .id("chatcmpl-1");
    }

    private static ChatResponse textChunk(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                baseMetadata().usage(new DefaultUsage(0, 0, 0)).build());
    }

    private static ChatResponse finishReasonChunk(String finishReason) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(""),
                        ChatGenerationMetadata.builder().finishReason(finishReason).build())),
                baseMetadata().usage(new DefaultUsage(0, 0, 0)).build());
    }

    /** OpenAI's final usage chunk: {@code choices: []}, so this response has no result at all. */
    private static ChatResponse usageChunk(int promptTokens, int completionTokens) {
        return new ChatResponse(List.of(),
                baseMetadata()
                        .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens))
                        .build());
    }

    private Flux<String> capturing(Flux<ChatResponse> chatResponseFlux) {
        return ChatStreamSupport.capturingContentFlux(chatResponseFlux, chatMessageService,
                new LiteLlmHeaderRegistry(Clock.systemUTC()), chatId, since, UUID.randomUUID());
    }

    /**
     * The text a client renders is unchanged by the capture — the finish-reason chunk's empty string
     * and the result-less usage chunk are still filtered out, exactly as {@code contentFlux} does.
     */
    @Test
    void emitsTheSameTextAsContentFluxAndPersistsTheTurnsTotalsOnce() {
        StepVerifier.create(capturing(Flux.just(
                        textChunk("hel"),
                        textChunk("lo"),
                        finishReasonChunk("STOP"),
                        usageChunk(1042, 259))))
                .expectNext("hel", "lo")
                .verifyComplete();

        ArgumentCaptor<ResponseMetadata> metadataCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<List<ModelCallMetadata>> callsCaptor = ArgumentCaptor.captor();

        verify(chatMessageService).updateResponseMetadata(eq(chatId), eq(since),
                metadataCaptor.capture(), callsCaptor.capture());

        ResponseMetadata responseMetadata = metadataCaptor.getValue();

        assertThat(responseMetadata.model()).isEqualTo(SERVER_REPORTED_MODEL);
        assertThat(responseMetadata.finishReason()).isEqualTo("stop");
        assertThat(responseMetadata.modelCalls()).isEqualTo(1);
        assertThat(responseMetadata.totalTokens()).isEqualTo(1301);

        assertThat(callsCaptor.getValue()).singleElement()
                .satisfies(call -> assertThat(call.promptTokens()).isEqualTo(1042));
    }

    /**
     * Every round trip of a tool-calling turn is summed into the one update, because the row is the
     * turn rather than the call.
     */
    @Test
    void sumsEveryRoundTripOfAToolCallingTurnIntoOneUpdate() {
        StepVerifier.create(capturing(Flux.just(
                        finishReasonChunk("TOOL_CALLS"),
                        usageChunk(1042, 88),
                        textChunk("the answer"),
                        finishReasonChunk("STOP"),
                        usageChunk(1380, 165))))
                .expectNext("the answer")
                .verifyComplete();

        ArgumentCaptor<ResponseMetadata> metadataCaptor = ArgumentCaptor.captor();

        verify(chatMessageService).updateResponseMetadata(eq(chatId), eq(since),
                metadataCaptor.capture(), anyList());

        assertThat(metadataCaptor.getValue().modelCalls()).isEqualTo(2);
        assertThat(metadataCaptor.getValue().totalTokens()).isEqualTo(2675);
        assertThat(metadataCaptor.getValue().finishReason()).isEqualTo("stop");
    }

    /**
     * A server that reported no usage leaves nothing to record, and must not cost the turn a query
     * and a write of nulls over whatever the row already holds.
     */
    @Test
    void persistsNothingWhenTheServerReportedNoUsage() {
        StepVerifier.create(capturing(Flux.just(textChunk("hello"))))
                .expectNext("hello")
                .verifyComplete();

        verifyNoInteractions(chatMessageService);
    }

    /**
     * A failed turn has no assistant row to attach anything to — the chat memory advisor never saved
     * one — so the error path must not write a partial accounting.
     */
    @Test
    void persistsNothingWhenTheStreamFails() {
        StepVerifier.create(capturing(Flux.concat(
                        Flux.just(textChunk("partial")),
                        Flux.error(new IllegalStateException("boom")))))
                .expectNext("partial")
                .verifyError(IllegalStateException.class);

        verifyNoInteractions(chatMessageService);
    }

    /**
     * A cancelled turn cancels the upstream flux rather than completing it, which is what keeps a
     * half-finished answer's counts off the row.
     */
    @Test
    void persistsNothingWhenTheTurnIsCancelled() {
        Sinks.Many<ChatResponse> chatResponses = Sinks.many().unicast().onBackpressureBuffer();

        StepVerifier.create(capturing(chatResponses.asFlux()))
                .then(() -> chatResponses.tryEmitNext(textChunk("partial")))
                .expectNext("partial")
                .thenCancel()
                .verify();

        verifyNoInteractions(chatMessageService);
    }

    /**
     * Bookkeeping must not cost the user their answer. Every chunk has already reached the client by
     * the time the write runs, and an exception escaping {@code doOnComplete} would turn this flux's
     * completion into an error for everything downstream — republishing a delivered turn as an error
     * frame over a failure to record token counts.
     */
    @Test
    void aFailedWriteDoesNotFailTheTurn() {
        doThrow(new IllegalStateException("the database is down"))
                .when(chatMessageService).updateResponseMetadata(any(), any(), any(), anyList());

        StepVerifier.create(capturing(Flux.just(
                        textChunk("hello"),
                        finishReasonChunk("STOP"),
                        usageChunk(1042, 259))))
                .expectNext("hello")
                .verifyComplete();

        verify(chatMessageService).updateResponseMetadata(eq(chatId), eq(since), any(), anyList());
    }
}
