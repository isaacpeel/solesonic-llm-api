package com.solesonic.service.prompt;

import com.solesonic.model.chat.ResponseMetadata;
import com.solesonic.model.chat.ResponseMetadataCapture;
import com.solesonic.service.chat.ChatMessageService;
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

import static com.solesonic.service.litellm.LiteLlmHeaderInterceptor.METADATA;
import static com.solesonic.service.litellm.LiteLlmHeaderInterceptor.TURN_ID;

/**
 * The two pieces every route that streams from a {@code ChatClient} needs identically: the options
 * the request is made with, and the reduction of a response stream to the text a client renders.
 */
public final class ChatStreamSupport {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamSupport.class);

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
     */
    public static OpenAiChatOptions.Builder chatOptions(String model, Duration timeout, UUID correlationId) {
        return OpenAiChatOptions.builder()
                .model(model)
                .streamUsage(true)
                .timeout(timeout)
                .extraBody(Map.of(METADATA, Map.of(TURN_ID, correlationId.toString())));
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
     * {@link #contentFlux} plus recording what the server reported about the turn, once the stream
     * completes.
     * <p>
     * {@code since} has to be taken before the call is made: the assistant row this attaches to is
     * written by the chat memory advisor partway through, and the timestamp is the only handle the
     * caller has on it — the advisor never hands its id back. The ordering that makes the lookup
     * find that row rather than miss it is not incidental: {@code MessageChatMemoryAdvisor} saves
     * from inside {@code MessageAggregator}'s own {@code doOnComplete}, registered upstream of this
     * one, and Reactor fires completion callbacks upstream first.
     * <p>
     * Deliberately {@code doOnComplete} rather than {@code doFinally}. A cancelled turn cancels this
     * flux and a failed one errors it, and neither leaves an assistant row to attach anything to —
     * so both skip the write for free rather than recording half a turn.
     */
    public static Flux<String> capturingContentFlux(Flux<ChatResponse> chatResponseFlux,
                                                    ChatMessageService chatMessageService,
                                                    LiteLlmHeaderRegistry liteLlmHeaderRegistry,
                                                    UUID chatId,
                                                    ZonedDateTime since,
                                                    UUID correlationId) {

        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        return contentFlux(chatResponseFlux.doOnNext(responseMetadataCapture::accept))
                .doOnComplete(() -> persist(responseMetadataCapture, chatMessageService, liteLlmHeaderRegistry,
                        chatId, since, correlationId));
    }

    /**
     * A turn nothing was reported for records nothing. Writing the empty case would cost a query and
     * replace whatever the row already holds with nulls, which reads as "the model used no tokens"
     * rather than "the server never said".
     * <p>
     * Bookkeeping, and bookkeeping must not cost the user their answer — the same stance
     * {@code DatabaseChatMemory.bindGeneratedImages} takes, for the same reason. Every chunk has
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
     * attach. {@code ToolCallService} is the only one: it never sets request options, taking them
     * from the hand-built tool-call model instead, and overriding them at the call site to smuggle
     * an id in would replace that model's own base URL and key along with them.
     */
    static void persist(ResponseMetadataCapture responseMetadataCapture,
                        ChatMessageService chatMessageService,
                        UUID chatId,
                        ZonedDateTime since) {

        ResponseMetadata responseMetadata = responseMetadataCapture.metadata();

        if (responseMetadata == null) {
            return;
        }

        try {
            chatMessageService.updateResponseMetadata(chatId, since, responseMetadata, responseMetadataCapture.calls());
        } catch (RuntimeException runtimeException) {
            log.error("Could not record response metadata for chat {}", chatId, runtimeException);
        }
    }
}
