package com.solesonic.service.prompt;

import com.solesonic.config.chat.ResponseMetadataCaptureAdvisor;
import com.solesonic.model.chat.ResponseMetadata;
import com.solesonic.model.chat.ResponseMetadataCapture;
import com.solesonic.model.chat.SessionTurnRecord;
import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.chat.ConversationTurn;
import com.solesonic.service.litellm.LiteLlmHeaderRegistry;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.solesonic.service.litellm.LiteLlmHeaderInterceptor.METADATA;
import static com.solesonic.service.litellm.LiteLlmHeaderInterceptor.TURN_ID;

/**
 * The two pieces every route that streams from a {@code ChatClient} needs identically: the options
 * the request is made with, and the reduction of a response stream to the text a client renders.
 */
public final class ChatStreamSupport {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamSupport.class);

    /**
     * llama.cpp's own streaming flag: without it, a streamed response carries no {@code timings}
     * object at all — confirmed against a raw SSE capture where the final usage-only chunk had
     * every standard field but no {@code timings} — unlike a non-streaming completion, which
     * includes the aggregate object regardless of this flag. LiteLLM passes it through unmodified
     * to any OpenAI-compatible custom provider, the same way it does the turn-id metadata below.
     */
    private static final String TIMINGS_PER_TOKEN = "timings_per_token";

    private ChatStreamSupport() {
    }

    /**
     * Asks the server for {@code stream_options.include_usage}, which is what puts the turn's token
     * counts on the stream's final chunk at all.
     * <p>
     * Pinning it rather than relying on the default is deliberate. Spring AI only defaults it to true
     * while no stream options are set: the moment anything sets one,
     * {@code OpenAiChatModel.createRequest} reads {@code includeUsage} out of it and a null there
     * becomes {@code false}. Setting any unrelated stream option elsewhere would otherwise silently
     * take the token counts away again.
     * <p>
     * {@code timeout} is equally load-bearing: {@code OpenAiChatModel.buildRequestOptions} always
     * sets a per-call {@code RequestOptions} timeout from {@code OpenAiChatOptions.getTimeout()},
     * which defaults to a hardcoded 60 seconds ({@code AbstractOpenAiOptions.DEFAULT_TIMEOUT}) when
     * left unset here — and that per-call value overrides the OkHttp client's own default timeout on
     * every request, {@code spring.ai.openai.chat.timeout} included. Passing it through explicitly is
     * what makes that property apply to streaming chat calls at all.
     * <p>
     * {@code correlationId} rides in the request body because that is the only channel into the
     * request the application controls — {@code buildRequestOptions} carries a timeout and nothing
     * else, so there is no per-request header to put it in. It is what lets
     * {@code LiteLlmHeaderInterceptor} file a response's {@code x-litellm-*} headers under the turn
     * that caused them; see {@code LiteLlmHeaderRegistry} for why the two cannot simply share a
     * thread. Set here, alongside {@code streamUsage}, so that no route can build options without
     * it and quietly lose its proxy accounting.
     * <p>
     * {@link #TIMINGS_PER_TOKEN} rides in the same {@code extraBody}, for the same reason: a route
     * that built its own options could otherwise stream from a llama.cpp-backed model and never see
     * {@code timings} at all, which is what {@link com.solesonic.model.chat.ResponseMetadataCapture}
     * needs for everything but {@code cachedPromptTokens}, {@code totalMillis} and {@code routedModel}.
     * <p>
     * {@code llmApiKey} is the user's own key, as a per-request {@code Authorization} header. A streamed
     * call runs on the SDK's dispatcher thread, out of reach of {@code LlmCredentialScope}, so the
     * header is the only way the user's key reaches it.
     */
    public static OpenAiChatOptions.Builder chatOptions(String model, Duration timeout, UUID correlationId, LlmApiKey llmApiKey) {
        return OpenAiChatOptions.builder()
                .model(model)
                .streamUsage(true)
                .timeout(timeout)
                .customHeaders(llmApiKey.authorizationHeaders())
                .extraBody(Map.of(
                        METADATA, Map.of(TURN_ID, correlationId.toString()),
                        TIMINGS_PER_TOKEN, true));
    }

    /**
     * Equivalent to {@code StreamResponseSpec.content()}.
     */
    public static Flux<String> contentFlux(Flux<ChatResponse> chatResponseFlux) {
        return chatResponseFlux
                .map(chatResponse -> Optional.ofNullable(chatResponse.getResult())
                        .map(Generation::getOutput)
                        .map(AbstractMessage::getText)
                        .orElse(""))
                .filter(StringUtils::isNotEmpty);
    }

    /**
     * {@link #contentFlux} plus ending the turn: saving its reply and recording what the server
     * reported about it, once the stream completes.
     * <p>
     * {@code responseMetadataCapture} is not fed from this flux. It has to be the one the call was
     * given as its {@link ResponseMetadataCaptureAdvisor#RESPONSE_METADATA_CAPTURE} advisor param,
     * which that advisor fills from beneath {@code ToolCallingAdvisor}: by the time a response
     * reaches this flux, every tool-call round has been filtered out and the last round's usage has
     * lost its native usage, and {@code tokens_per_second} with it.
     * <p>
     * On completion the turn's assistant row is written first, from the text the client was shown,
     * and the metadata attached to it after, found by {@code since}. A completed stream means the
     * session advisor has already stored the reply: it stores it inside {@code MessageAggregator}'s own
     * {@code doOnComplete}, registered upstream of this one, and Reactor fires completion callbacks
     * upstream first.
     * <p>
     * A failed or cancelled turn saves nothing and only links the user row, if the advisor stored it.
     * A completion that fails errors the turn without also abandoning it.
     */
    public static Flux<String> capturingContentFlux(Flux<ChatResponse> chatResponseFlux,
                                                    ResponseMetadataCapture responseMetadataCapture,
                                                    ChatMessageService chatMessageService,
                                                    LiteLlmHeaderRegistry liteLlmHeaderRegistry,
                                                    ZonedDateTime since,
                                                    UUID correlationId,
                                                    ConversationTurn conversationTurn) {

        StringBuilder reply = new StringBuilder();
        AtomicBoolean completed = new AtomicBoolean();

        return chatResponseFlux
                .doOnNext(chatResponse -> recordFinishReasons(chatResponse, conversationTurn.record()))
                .transform(ChatStreamSupport::contentFlux)
                .doOnNext(reply::append)
                .doOnComplete(() -> {
                    completed.set(true);
                    conversationTurn.complete(reply.toString());
                    persist(responseMetadataCapture, chatMessageService, liteLlmHeaderRegistry,
                            conversationTurn.chatId(), since, correlationId);
                })
                .doOnError(_ -> {
                    if (!completed.get()) {
                        conversationTurn.abandon();
                    }
                })
                .doOnCancel(() -> {
                    if (!completed.get()) {
                        conversationTurn.abandon();
                    }
                });
    }

    static void recordFinishReasons(ChatResponse chatResponse, SessionTurnRecord sessionTurnRecord) {
        for (Generation generation : chatResponse.getResults()) {
            sessionTurnRecord.recordFinishReason(generation.getMetadata().getFinishReason());
        }
    }

    /**
     * A turn nothing was reported for records nothing. Writing the empty case would cost a query and
     * replace whatever the row already holds with nulls, which reads as "the model used no tokens"
     * rather than "the server never said".
     * <p>
     * Bookkeeping, and bookkeeping must not cost the user their answer — the same stance
     * {@code ConversationSessionService} takes on binding images, for the same reason. Every chunk has
     * already reached the client by the time this runs, and an exception escaping a
     * {@code doOnComplete} turns that flux's completion into an error for everything downstream:
     * a delivered turn would be republished as an error frame over a failed write of token counts.
     * What is lost instead is one turn's accounting, which is worth a log line and nothing more.
     */
    static void persist(ResponseMetadataCapture responseMetadataCapture,
                        ChatMessageService chatMessageService,
                        LiteLlmHeaderRegistry liteLlmHeaderRegistry,
                        UUID chatId,
                        ZonedDateTime since,
                        UUID correlationId) {

        //Collected unconditionally and ahead of the early return: a turn that reported no usage
        //still made HTTP calls, and leaving its entry behind would hold it until the TTL swept it.
        //It also has to precede metadata(), which derives the turn's routed model from these.
        responseMetadataCapture.applyLiteLlmCalls(liteLlmHeaderRegistry.take(correlationId));

        persist(responseMetadataCapture, chatMessageService, chatId, since);
    }

    /**
     * The same write for a route that sends no correlation id, and so has no proxy headers to
     * attach. {@code ToolCallService} is the only one: its task client adds nothing to the hand-built
     * tool-call model's options but the user's key.
     */
    static void persist(ResponseMetadataCapture responseMetadataCapture,
                        ChatMessageService chatMessageService,
                        UUID chatId,
                        ZonedDateTime since) {

        ResponseMetadata responseMetadata = responseMetadataCapture.metadata();

        if (responseMetadata == null) {
            return;
        }

        log.info("Captured response metadata for chat {}: {}, calls: {}",
                chatId, responseMetadata, responseMetadataCapture.calls());

        try {
            chatMessageService.updateResponseMetadata(chatId, since, responseMetadata, responseMetadataCapture.calls());
        } catch (RuntimeException runtimeException) {
            log.error("Could not record response metadata for chat {}", chatId, runtimeException);
        }
    }
}
