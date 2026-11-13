package com.solesonic.config.chat;

import com.solesonic.model.chat.SessionTurnRecord;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.solesonic.config.chat.SessionEventCorrelationAdvisor.SESSION_TURN_RECORD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session advisors as this application wires them, in front of a scripted model and the real
 * {@code ChatClient} tool loop: what each model round is shown, what the session keeps, and what the
 * turn learns about its own writes. This is what the migration relies on from the library, pinned
 * without a model server.
 */
class SessionMemoryChainTest {

    private final SessionService sessionService = DefaultSessionService.builder()
            .sessionRepository(InMemorySessionRepository.builder().build())
            .build();

    private final ConversationAdvisors conversationAdvisors = new SessionMemoryConfig()
            .conversationAdvisors(sessionService, 20, 10, 16000);

    private final String sessionId = UUID.randomUUID().toString();

    private final ScriptedChatModel chatModel = new ScriptedChatModel();

    private final ChatClient chatClient = ChatClient.builder(chatModel)
            .defaultAdvisors(conversationAdvisors.advisors())
            .defaultAdvisors(new UserMessageMergingAdvisor())
            .build();

    record LookupRequest(String query) {
    }

    private static ToolCallback lookup(boolean returnDirect) {
        return FunctionToolCallback.builder("lookup", (LookupRequest lookupRequest) -> "result for " + lookupRequest.query())
                .description("Looks something up")
                .inputType(LookupRequest.class)
                .toolMetadata(ToolMetadata.builder().returnDirect(returnDirect).build())
                .build();
    }

    private static ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse toolCall(String callId, String query) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", "lookup", "{\"query\":\"" + query + "\"}")))
                .build())));
    }

    private String ask(String question, SessionTurnRecord sessionTurnRecord, ToolCallback... tools) {
        return chatClient.prompt()
                .user(question)
                .tools((Object[]) tools)
                .advisors(SessionAdvisors.session(sessionId))
                .advisors(advisorSpec -> advisorSpec.param(SESSION_TURN_RECORD, sessionTurnRecord))
                .call()
                .content();
    }

    private static List<String> shape(List<Message> messages) {
        return messages.stream().map(SessionMemoryChainTest::shape).toList();
    }

    private static String shape(Message message) {
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            return "TOOL:" + toolResponseMessage.getResponses().getFirst().responseData();
        }

        if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
            return "CALL:" + assistantMessage.getToolCalls().getFirst().arguments();
        }

        return message.getMessageType() + ":" + message.getText();
    }

    @Test
    void theSecondTurnShowsTheFirstExactlyOnce() {
        chatModel.answers(text("Hello Isaac"), text("You are Isaac"));

        ask("my name is Isaac", new SessionTurnRecord());
        ask("what is my name?", new SessionTurnRecord());

        assertThat(shape(chatModel.prompts().getLast())).containsExactly(
                "USER:my name is Isaac", "ASSISTANT:Hello Isaac", "USER:what is my name?");
    }

    /**
     * The id the route links the user row by is the id the session stores the message under.
     */
    @Test
    void theTurnRecordNamesTheEventHoldingTheUsersMessage() {
        chatModel.answers(text("hi"));
        SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();

        ask("hello", sessionTurnRecord);

        SessionEvent userEvent = sessionService.getEvents(sessionId).stream()
                .filter(event -> event.getMessageType() == MessageType.USER)
                .findFirst()
                .orElseThrow();

        assertThat(sessionTurnRecord.userEventId()).contains(userEvent.getId());
    }

    /**
     * A completed stream means the reply is already in the session: the route writes the assistant row
     * on completion and marks it as stored by the advisor.
     */
    @Test
    void aStreamedReplyIsStoredBeforeTheStreamCompletes() {
        chatModel.answers(text("streamed answer"));
        SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();
        AtomicReference<List<Message>> storedAtCompletion = new AtomicReference<>();

        chatClient.prompt()
                .user("stream it")
                .advisors(SessionAdvisors.session(sessionId))
                .advisors(advisorSpec -> advisorSpec.param(SESSION_TURN_RECORD, sessionTurnRecord))
                .stream()
                .chatResponse()
                .doOnComplete(() -> storedAtCompletion.set(sessionService.getMessages(sessionId)))
                .blockLast();

        assertThat(shape(storedAtCompletion.get())).containsExactly("USER:stream it", "ASSISTANT:streamed answer");
        assertThat(sessionTurnRecord.userEventId()).isPresent();
    }

    /**
     * The live turn carries its attachment block as its own message ahead of the user's words, and the
     * session already ends with it: the request overlaps the history by exactly that message, so the
     * model reads it once.
     */
    @Test
    void anAttachmentContextTheSessionEndsWithReachesTheModelOnce() {
        String context = "The user attached 1 image(s). sky.png: an overcast sky";
        sessionService.create(CreateSessionRequest.builder().id(sessionId).build());
        sessionService.appendMessage(sessionId, new UserMessage(context));
        chatModel.answers(text("grey"));

        chatClient.prompt()
                .messages(new UserMessage(context))
                .user("what colour is the sky?")
                .advisors(SessionAdvisors.session(sessionId))
                .call()
                .content();

        List<Message> prompt = chatModel.prompts().getLast();
        assertThat(prompt).singleElement().satisfies(message ->
                assertThat(message.getText()).isEqualTo(context + System.lineSeparator() + System.lineSeparator() + "what colour is the sky?"));
    }

    /**
     * Each round of a tool turn is a valid prompt, every call followed by its result; the session keeps
     * the whole exchange, and the next turn replays it (D1).
     */
    @Test
    void aMultiRoundToolTurnIsReplayedWholeOnTheNextTurn() {
        chatModel.answers(toolCall("call-1", "first"), toolCall("call-2", "second"), text("done"), text("ok"));
        SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();

        assertThat(ask("look two things up", sessionTurnRecord, lookup(false))).isEqualTo("done");
        ask("thanks", new SessionTurnRecord());

        List<List<Message>> prompts = chatModel.prompts();
        assertThat(shape(prompts.get(1))).containsExactly(
                "USER:look two things up", "CALL:{\"query\":\"first\"}", "TOOL:\"result for first\"");
        assertThat(shape(prompts.get(2))).containsExactly(
                "USER:look two things up", "CALL:{\"query\":\"first\"}", "TOOL:\"result for first\"",
                "CALL:{\"query\":\"second\"}", "TOOL:\"result for second\"");
        assertThat(shape(prompts.get(3))).containsExactly(
                "USER:look two things up", "CALL:{\"query\":\"first\"}", "TOOL:\"result for first\"",
                "CALL:{\"query\":\"second\"}", "TOOL:\"result for second\"", "ASSISTANT:done", "USER:thanks");

        SessionEvent userEvent = sessionService.getEvents(sessionId).getFirst();
        assertThat(sessionTurnRecord.userEventId()).contains(userEvent.getId());
    }

    /**
     * A {@code returnDirect} tool ends the loop with no model round: the response says so, the session
     * holds the call answered by its result and no assistant message, and the next prompt is still
     * valid.
     */
    @Test
    void aReturnDirectToolEndsTheTurnWithoutAModelRound() {
        chatModel.answers(toolCall("call-1", "invoice"), text("next answer"));

        ChatResponse chatResponse = chatClient.prompt()
                .user("create the invoice")
                .tools(lookup(true))
                .advisors(SessionAdvisors.session(sessionId))
                .call()
                .chatResponse();

        assertThat(chatResponse).isNotNull();
        assertThat(chatResponse.getResults()).extracting(generation -> generation.getMetadata().getFinishReason())
                .contains(ToolExecutionResult.FINISH_REASON);

        SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();
        chatResponse.getResults().forEach(generation -> sessionTurnRecord.recordFinishReason(generation.getMetadata().getFinishReason()));
        assertThat(sessionTurnRecord.endedWithoutModelRound()).isTrue();

        assertThat(sessionService.getMessages(sessionId)).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);

        ask("and now?", new SessionTurnRecord());

        assertThat(shape(chatModel.prompts().getLast())).containsExactly(
                "USER:create the invoice", "CALL:{\"query\":\"invoice\"}", "TOOL:\"result for invoice\"", "USER:and now?");
    }

    /**
     * A model that answers from a script and keeps every prompt it was sent, the way a model server
     * would see it.
     */
    @SuppressWarnings("all")
    private static final class ScriptedChatModel implements ChatModel {
        private final Deque<ChatResponse> answers = new ArrayDeque<>();
        private final List<List<Message>> prompts = new ArrayList<>();

        void answers(ChatResponse... chatResponses) {
            answers.addAll(List.of(chatResponses));
        }

        List<List<Message>> prompts() {
            return prompts;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(List.copyOf(prompt.getInstructions()));

            return answers.removeFirst();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
