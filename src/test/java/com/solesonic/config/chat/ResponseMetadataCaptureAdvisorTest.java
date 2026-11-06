package com.solesonic.config.chat;

import com.openai.core.JsonValue;
import com.openai.models.completions.CompletionUsage;
import com.solesonic.model.chat.ModelCallMetadata;
import com.solesonic.model.chat.ResponseMetadata;
import com.solesonic.model.chat.ResponseMetadataCapture;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static com.solesonic.config.chat.ResponseMetadataCaptureAdvisor.RESPONSE_METADATA_CAPTURE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs a real {@code ChatClient}, with the {@code ToolCallingAdvisor} Spring AI registers itself,
 * against a model that streams what Spring AI's OpenAI model streams. That advisor is the thing being
 * worked around: above it, tool-call rounds are filtered out and the last round's usage is replaced
 * by a sum with no native usage, so only a capture fed from beneath it sees each call as it was.
 */
class ResponseMetadataCaptureAdvisorTest {

    private static final String MODEL = "qwen3-8b-instruct-q6";
    private static final String TOOL_NAME = "web_search";

    private static ChatResponseMetadata.Builder baseMetadata(String id) {
        return ChatResponseMetadata.builder()
                .model(MODEL)
                .id(id);
    }

    private static ChatResponse textChunk(String id, String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                baseMetadata(id).usage(new DefaultUsage(0, 0, 0)).build());
    }

    @SuppressWarnings("all")
    private static ChatResponse finishReasonChunk(String id, String finishReason) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(""),
                        ChatGenerationMetadata.builder().finishReason(finishReason).build())),
                baseMetadata(id).usage(new DefaultUsage(0, 0, 0)).build());
    }

    private static ChatResponse toolCallChunk(String id) {
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id + "-call", "function", TOOL_NAME, "{}")))
                .build();

        return new ChatResponse(
                List.of(new Generation(toolCall, ChatGenerationMetadata.builder().finishReason("TOOL_CALLS").build())),
                baseMetadata(id).usage(new DefaultUsage(0, 0, 0)).build());
    }

    /**
     * OpenAI's final usage chunk, with the SDK usage kept as the native object and the proxy's
     * {@code tokens_per_second} among its additional properties, as openai-java parses it.
     */
    private static ChatResponse usageChunk(String id, int promptTokens, int completionTokens, double tokensPerSecond) {
        CompletionUsage completionUsage = CompletionUsage.builder()
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(promptTokens + completionTokens)
                .putAdditionalProperty("tokens_per_second", JsonValue.from(tokensPerSecond))
                .build();

        return new ChatResponse(List.of(),
                baseMetadata(id)
                        .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens,
                                completionUsage))
                        .build());
    }

    private static ResponseMetadataCapture stream(List<Flux<ChatResponse>> rounds) {
        ScriptedChatModel chatModel = new ScriptedChatModel(rounds);

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new ResponseMetadataCaptureAdvisor())
                .build();

        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        List<ChatResponse> received = chatClient.prompt()
                .user("what happened today?")
                .tools(new StubToolCallback())
                .advisors(advisorSpec -> advisorSpec.param(RESPONSE_METADATA_CAPTURE, responseMetadataCapture))
                .stream()
                .chatResponse()
                .collectList()
                .block();

        assertThat(received).isNotEmpty();
        assertThat(chatModel.calls()).isEqualTo(rounds.size());

        return responseMetadataCapture;
    }

    @Test
    void recordsEveryRoundTripOfAToolCallingTurnWithItsOwnUsageAndRate() {
        ResponseMetadataCapture responseMetadataCapture = stream(List.of(
                Flux.just(toolCallChunk("chatcmpl-1"), usageChunk("chatcmpl-1", 2400, 30, 101.5)),
                Flux.just(toolCallChunk("chatcmpl-2"), usageChunk("chatcmpl-2", 2510, 40, 98.25)),
                Flux.just(
                        textChunk("chatcmpl-3", "the answer"),
                        finishReasonChunk("chatcmpl-3", "STOP"),
                        usageChunk("chatcmpl-3", 2710, 300, 87.75))));

        List<ModelCallMetadata> calls = responseMetadataCapture.calls();

        assertThat(calls).extracting(ModelCallMetadata::id)
                .containsExactly("chatcmpl-1", "chatcmpl-2", "chatcmpl-3");
        assertThat(calls).extracting(ModelCallMetadata::finishReason)
                .containsExactly("tool_calls", "tool_calls", "stop");
        assertThat(calls).extracting(ModelCallMetadata::promptTokens)
                .containsExactly(2400, 2510, 2710);
        assertThat(calls).extracting(ModelCallMetadata::completionTokens)
                .containsExactly(30, 40, 300);
        assertThat(calls).extracting(ModelCallMetadata::tokensPerSecond)
                .containsExactly(101.5, 98.25, 87.75);

        ResponseMetadata responseMetadata = Objects.requireNonNull(responseMetadataCapture.metadata());

        assertThat(responseMetadata.modelCalls()).isEqualTo(3);
        assertThat(responseMetadata.promptTokens()).isEqualTo(7620);
        assertThat(responseMetadata.completionTokens()).isEqualTo(370);
        assertThat(responseMetadata.totalTokens()).isEqualTo(7990);
        assertThat(responseMetadata.tokensPerSecond()).isEqualTo(87.75);
        assertThat(responseMetadata.finishReason()).isEqualTo("stop");
    }

    @Test
    void recordsASingleRoundTurnAsOneCall() {
        ResponseMetadataCapture responseMetadataCapture = stream(List.of(
                Flux.just(
                        textChunk("chatcmpl-1", "hel"),
                        textChunk("chatcmpl-1", "lo"),
                        finishReasonChunk("chatcmpl-1", "STOP"),
                        usageChunk("chatcmpl-1", 1042, 259, 222.23))));

        assertThat(responseMetadataCapture.calls()).singleElement().satisfies(call -> {
            assertThat(call.promptTokens()).isEqualTo(1042);
            assertThat(call.completionTokens()).isEqualTo(259);
            assertThat(call.tokensPerSecond()).isEqualTo(222.23);
        });

        ResponseMetadata responseMetadata = Objects.requireNonNull(responseMetadataCapture.metadata());

        assertThat(responseMetadata.modelCalls()).isEqualTo(1);
        assertThat(responseMetadata.totalTokens()).isEqualTo(1301);
        assertThat(responseMetadata.tokensPerSecond()).isEqualTo(222.23);
    }

    @Test
    void passesARequestWithoutACaptureThroughUntouched() {
        ChatClient chatClient = ChatClient.builder(new ScriptedChatModel(List.of(
                        Flux.just(textChunk("chatcmpl-1", "hello"), usageChunk("chatcmpl-1", 10, 2, 50.0)))))
                .defaultAdvisors(new ResponseMetadataCaptureAdvisor())
                .build();

        List<String> content = chatClient.prompt()
                .user("hi")
                .stream()
                .content()
                .collectList()
                .block();

        assertThat(content).containsExactly("hello");
    }

    /**
     * Answers each streamed call with the next scripted round, the way a model answers each round
     * trip of the tool-calling loop.
     */
    private static final class ScriptedChatModel implements ChatModel {

        private final List<Flux<ChatResponse>> rounds;
        private final AtomicInteger calls = new AtomicInteger();

        private ScriptedChatModel(List<Flux<ChatResponse>> rounds) {
            this.rounds = rounds;
        }

        @Override
        public @NonNull ChatResponse call(@NonNull Prompt prompt) {
            throw new UnsupportedOperationException("streaming only");
        }

        @Override
        public @NonNull Flux<ChatResponse> stream(@NonNull Prompt prompt) {
            return rounds.get(calls.getAndIncrement());
        }

        @Override
        public @NonNull ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class StubToolCallback implements ToolCallback {

        @Override
        public @NonNull ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(TOOL_NAME)
                    .description("Searches the web")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public @NonNull String call(@NonNull String toolInput) {
            return "search results";
        }
    }
}
