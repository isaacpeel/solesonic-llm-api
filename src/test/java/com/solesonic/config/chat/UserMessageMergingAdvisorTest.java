package com.solesonic.config.chat;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs a real {@code ChatClient} against a model that records what it was sent, with a stand-in for
 * the retrieval advisor at its real order, so the test pins both that adjacent user messages reach
 * the model as one and that retrieval still saw only the user's own words.
 */
class UserMessageMergingAdvisorTest {

    private static final String SEPARATOR = System.lineSeparator() + System.lineSeparator();
    private static final String IMAGE_CONTEXT = "Attached image: a red barn in a snowy field";
    private static final String QUESTION = "make this look like a painting";

    @Test
    void joinsImageContextAndTheQuestionAfterRetrievalRewroteOnlyTheQuestion() {
        RecordingChatModel chatModel = new RecordingChatModel();
        RetrievalStandIn retrievalStandIn = new RetrievalStandIn();

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new UserMessageMergingAdvisor(), retrievalStandIn)
                .build();

        List<String> content = chatClient.prompt()
                .system("you are helpful")
                .messages(new UserMessage(IMAGE_CONTEXT))
                .user(QUESTION)
                .stream()
                .content()
                .collectList()
                .block();

        assertThat(content).containsExactly("done");
        assertThat(retrievalStandIn.rewrittenQuery).isEqualTo(QUESTION);

        List<Message> sent = chatModel.lastPrompt.getInstructions();

        assertThat(sent).extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER);
        assertThat(sent.get(1).getText())
                .isEqualTo(IMAGE_CONTEXT + SEPARATOR + RetrievalStandIn.augmented(QUESTION));
    }

    @Test
    void joinsAReplayedImageTurnAndLeavesTheRestOfTheHistoryAlternating() {
        List<Message> history = List.of(
                new SystemMessage("you are helpful"),
                new UserMessage(IMAGE_CONTEXT),
                new UserMessage(QUESTION),
                new AssistantMessage("here it is"),
                new UserMessage("thanks"));

        List<Message> merged = UserMessageMergingAdvisor.mergeAdjacentUserMessages(history);

        assertThat(merged).extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
        assertThat(merged.get(1).getText()).isEqualTo(IMAGE_CONTEXT + SEPARATOR + QUESTION);
        assertThat(merged.get(3).getText()).isEqualTo("thanks");
    }

    @Test
    void joinsUserMessagesLeftAdjacentByATurnThatSavedNoReply() {
        List<Message> history = List.of(
                new UserMessage("first try"),
                new UserMessage("second try"),
                new UserMessage("third try"));

        List<Message> merged = UserMessageMergingAdvisor.mergeAdjacentUserMessages(history);

        assertThat(merged).singleElement()
                .extracting(Message::getText)
                .isEqualTo("first try" + SEPARATOR + "second try" + SEPARATOR + "third try");
    }

    @Test
    void leavesAToolCallingRoundUntouched() {
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "generate_image", "{}")))
                .build();

        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "generate_image", "{\"imageId\":\"1\"}")))
                .build();

        List<Message> round = List.of(new UserMessage(QUESTION), toolCall, toolResponse);

        assertThat(UserMessageMergingAdvisor.mergeAdjacentUserMessages(round)).containsExactlyElementsOf(round);
    }

    @Test
    void passesAnAlternatingRequestThroughAsTheSameInstance() {
        ChatClientRequest chatClientRequest = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new SystemMessage("system"), new UserMessage(QUESTION))))
                .build();

        assertThat(UserMessageMergingAdvisor.merged(chatClientRequest)).isSameAs(chatClientRequest);
    }

    @Test
    void runsAfterMemoryAndRetrievalAndBeforeTheMetadataCapture() {
        assertThat(new UserMessageMergingAdvisor().getOrder())
                .isGreaterThan(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER)
                .isGreaterThan(RetrievalStandIn.RETRIEVAL_ORDER)
                .isLessThan(ResponseMetadataCaptureAdvisor.ORDER);
    }

    /**
     * Rewrites the last user message the way the retrieval advisor does, at the retrieval advisor's
     * default order, and records the query it saw.
     */
    private static final class RetrievalStandIn implements CallAdvisor, StreamAdvisor {

        private static final int RETRIEVAL_ORDER = 0;

        private String rewrittenQuery;

        private static String augmented(String query) {
            return "Context information is below." + System.lineSeparator() + "Query: " + query;
        }

        private ChatClientRequest rewrite(ChatClientRequest chatClientRequest) {
            Prompt prompt = chatClientRequest.prompt();
            rewrittenQuery = prompt.getUserMessage().getText();

            return chatClientRequest.mutate()
                    .prompt(prompt.augmentUserMessage(augmented(rewrittenQuery)))
                    .build();
        }

        @Override
        public @NonNull ChatClientResponse adviseCall(@NonNull ChatClientRequest chatClientRequest,
                                                      CallAdvisorChain callAdvisorChain) {
            return callAdvisorChain.nextCall(rewrite(chatClientRequest));
        }

        @Override
        public @NonNull Flux<ChatClientResponse> adviseStream(@NonNull ChatClientRequest chatClientRequest,
                                                              StreamAdvisorChain streamAdvisorChain) {
            return streamAdvisorChain.nextStream(rewrite(chatClientRequest));
        }

        @Override
        public @NonNull String getName() {
            return RetrievalStandIn.class.getSimpleName();
        }

        @Override
        public int getOrder() {
            return RETRIEVAL_ORDER;
        }
    }

    private static final class RecordingChatModel implements ChatModel {

        private Prompt lastPrompt;

        @Override
        public @NonNull ChatResponse call(@NonNull Prompt prompt) {
            throw new UnsupportedOperationException("streaming only");
        }

        @Override
        public @NonNull Flux<ChatResponse> stream(@NonNull Prompt prompt) {
            lastPrompt = prompt;

            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
        }

        @Override
        public @NonNull ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
