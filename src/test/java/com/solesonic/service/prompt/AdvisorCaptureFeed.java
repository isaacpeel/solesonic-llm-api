package com.solesonic.service.prompt;

import com.solesonic.model.chat.ResponseMetadataCapture;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.solesonic.config.chat.ResponseMetadataCaptureAdvisor.RESPONSE_METADATA_CAPTURE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Stands in for {@code ResponseMetadataCaptureAdvisor} against a mocked {@code ChatClient}: takes the
 * capture out of the advisor params the call was given, and feeds it every response the mocked model
 * streams — which is all the real advisor does from beneath the tool-calling loop.
 */
final class AdvisorCaptureFeed {

    private final AtomicReference<ResponseMetadataCapture> responseMetadataCapture = new AtomicReference<>();

    Flux<ChatResponse> feeding(Flux<ChatResponse> chatResponses) {
        return Flux.defer(() -> chatResponses.doOnNext(chatResponse ->
                Objects.requireNonNull(responseMetadataCapture.get(), "the call was given no " + RESPONSE_METADATA_CAPTURE)
                        .accept(chatResponse)));
    }

    void readAdvisorParamsOf(ChatClient.ChatClientRequestSpec requestSpec) {
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);

        lenient().when(advisorSpec.param(anyString(), any())).thenAnswer(invocation -> {
            if (RESPONSE_METADATA_CAPTURE.equals(invocation.getArgument(0))
                    && invocation.getArgument(1) instanceof ResponseMetadataCapture capture) {
                responseMetadataCapture.set(capture);
            }

            return advisorSpec;
        });

        doAnswer(invocation -> {
            Consumer<ChatClient.AdvisorSpec> advisorSpecConsumer = invocation.getArgument(0);
            advisorSpecConsumer.accept(advisorSpec);

            return requestSpec;
        }).when(requestSpec).advisors(ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any());
    }
}
