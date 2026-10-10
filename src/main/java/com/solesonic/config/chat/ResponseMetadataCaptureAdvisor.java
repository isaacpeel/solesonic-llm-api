package com.solesonic.config.chat;

import com.solesonic.model.chat.ResponseMetadataCapture;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Feeds a turn's {@link ResponseMetadataCapture} every response of every model call, exactly as the
 * model produced it.
 * <p>
 * It has to sit beneath {@code ToolCallingAdvisor}, which runs the chain below it once per round
 * trip. Above it, the stream has already been rewritten: tool-call rounds are filtered out, and the
 * earlier rounds' usage is added onto the last round's usage chunk as a new {@code DefaultUsage} with
 * no native usage — dropping the {@code CompletionUsage} that carries {@code tokens_per_second}.
 * <p>
 * The capture travels in the request context under {@link #RESPONSE_METADATA_CAPTURE}, as an advisor
 * param; every round's request is built from that context, so each round reaches the same capture.
 * A request without one passes through untouched.
 */
public class ResponseMetadataCaptureAdvisor implements StreamAdvisor {

    public static final String RESPONSE_METADATA_CAPTURE = "responseMetadataCapture";

    /**
     * Next to the model, so nothing between the two can rewrite a response first. Only the model
     * call advisor itself, at {@link Ordered#LOWEST_PRECEDENCE}, is closer.
     */
    static final int ORDER = Ordered.LOWEST_PRECEDENCE - 1;

    @Override
    public @NonNull Flux<ChatClientResponse> adviseStream(@NonNull ChatClientRequest chatClientRequest,
                                                          StreamAdvisorChain streamAdvisorChain) {

        Flux<ChatClientResponse> chatClientResponses = streamAdvisorChain.nextStream(chatClientRequest);

        if (!(chatClientRequest.context().get(RESPONSE_METADATA_CAPTURE) instanceof ResponseMetadataCapture responseMetadataCapture)) {
            return chatClientResponses;
        }

        return chatClientResponses.doOnNext(chatClientResponse -> {
            ChatResponse chatResponse = chatClientResponse.chatResponse();

            if (chatResponse != null) {
                responseMetadataCapture.accept(chatResponse);
            }
        });
    }

    @Override
    public @NonNull String getName() {
        return ResponseMetadataCaptureAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
