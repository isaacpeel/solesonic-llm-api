package com.solesonic.service.a2a;

import com.solesonic.config.JacksonConfig;
import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.config.a2a.A2AAuthInterceptor;
import com.solesonic.config.a2a.A2AClientProperties;
import com.solesonic.config.a2a.LiteLlmA2AAuthInterceptor;
import com.solesonic.exception.a2a.A2AAgentUnavailableException;
import com.solesonic.mcp.client.TokenExchangeService;
import com.solesonic.model.a2a.A2AAgentSource;
import com.solesonic.model.a2a.A2AElicitationExtension;
import com.solesonic.model.chat.ElicitationEvent;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.security.McpFilterService;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.chat.events.ElicitationService;
import com.solesonic.service.chat.events.NotificationService;
import io.modelcontextprotocol.spec.McpSchema;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor;
import org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.MessageType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class A2AAgentServiceTest {

    private static final UUID CHAT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ELICITATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Duration AGENT_TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private A2AAgentRegistry agentRegistry;

    @Mock
    private TokenExchangeService tokenExchangeService;

    @Mock
    private McpFilterService mcpFilterService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private A2AStickyAgentService stickyAgentService;

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private ElicitationService elicitationService;

    private final JsonMapper jsonMapper = new JacksonConfig().jsonMapper();

    private A2AAgentService agentService;

    @BeforeEach
    void setUp() {
        A2AClientProperties properties = new A2AClientProperties(false, AGENT_TIMEOUT.toSeconds(), "http://agents",
                new A2AClientProperties.Litellm("http://litellm:4000", "sk-izzybot"));

        agentService = new A2AAgentService(agentRegistry, tokenExchangeService, mcpFilterService,
                notificationService, stickyAgentService, chatMessageService, elicitationService, jsonMapper, properties);
    }

    /**
     * Drives one turn the way {@code delegate} does, without an SDK client: disposing the turn's sink
     * disposes the turn.
     */
    private Flux<String> turnOf(Consumer<A2AAgentService.DelegatedTurn> script) {
        return Flux.create(sink -> {
            A2AAgentService.DelegatedTurn turn = agentService.turn(CHAT_ID, sink);
            sink.onDispose(turn::dispose);
            script.accept(turn);
        });
    }

    private static Task task(String taskId, TaskState state, List<Artifact> artifacts) {
        return Task.builder()
                .id(taskId)
                .contextId("context-1")
                .status(new TaskStatus(state))
                .artifacts(artifacts)
                .build();
    }

    private static Artifact artifact(String text) {
        return Artifact.builder()
                .artifactId("artifact-1")
                .parts(new TextPart(text))
                .build();
    }

    private static Message agentMessage(String text) {
        return Message.builder()
                .role(Message.Role.ROLE_AGENT)
                .parts(new TextPart(text))
                .messageId("message-1")
                .build();
    }

    private static Map<String, Object> requestedSchema() {
        return Map.of("type", "object", "properties", Map.of("choice", Map.of("type", "string")));
    }

    private static Message promptMessage() {
        return Message.builder()
                .role(Message.Role.ROLE_AGENT)
                .messageId("prompt-1")
                .parts(new DataPart(Map.of("elicitation", Map.of(
                        "mode", "form",
                        "message", "Alex, play or pass?",
                        "requestedSchema", requestedSchema(),
                        "_meta", Map.of("feud/prompt", "play_or_pass")))))
                .build();
    }

    private static TaskUpdateEvent update(TaskArtifactUpdateEvent artifactEvent) {
        return new TaskUpdateEvent(task(artifactEvent.taskId(), TaskState.TASK_STATE_WORKING, null), artifactEvent);
    }

    private static TaskUpdateEvent update(TaskStatusUpdateEvent statusEvent) {
        return new TaskUpdateEvent(task(statusEvent.taskId(), TaskState.TASK_STATE_WORKING, null), statusEvent);
    }

    private static TaskUpdateEvent text(String taskId, String text) {
        return update(new TaskArtifactUpdateEvent(taskId, artifact(text), "context-1", false, true, null));
    }

    private static TaskUpdateEvent status(String taskId, TaskState state) {
        return update(new TaskStatusUpdateEvent(taskId, new TaskStatus(state), "context-1", null));
    }

    @SuppressWarnings("all")
    private static TaskUpdateEvent prompt(String taskId) {
        return update(new TaskStatusUpdateEvent(taskId,
                new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, promptMessage(), null), "context-1", null));
    }

    private List<String> savedReplies() {
        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageService, atLeastOnce()).save(saved.capture());

        assertThat(saved.getAllValues()).allSatisfy(chatMessage -> {
            assertThat(chatMessage.getChatId()).isEqualTo(CHAT_ID);
            assertThat(chatMessage.getMessageType()).isEqualTo(MessageType.ASSISTANT);
        });

        return saved.getAllValues().stream().map(ChatMessage::getMessage).toList();
    }

    private void promptIsRaised(Mono<McpSchema.ElicitResult> answer) {
        when(stickyAgentService.activateTask(CHAT_ID, "task-3")).thenReturn(Mono.empty());
        when(elicitationService.prepareElicitation(CHAT_ID)).thenReturn(new ElicitationService.ElicitationHandle(ELICITATION_ID));
        when(elicitationService.awaitResultAsync(CHAT_ID, ELICITATION_ID)).thenReturn(answer);
    }

    @Test
    void anAgentHostAgentIsCalledWithTheUsersOnBehalfOfToken() {
        ClientCallInterceptor interceptor = agentService.interceptor(A2AAgentSource.AGENT_HOST, "user-jwt");

        assertThat(interceptor).isInstanceOf(A2AAuthInterceptor.class);
    }

    /**
     * The user's token never goes to LiteLLM: the gateway interceptor neither exchanges it nor forwards it.
     */
    @Test
    void aLiteLlmAgentIsCalledWithTheVirtualKeyAndNeverTheUsersToken() {
        ClientCallInterceptor interceptor = agentService.interceptor(A2AAgentSource.LITELLM, "user-jwt");

        PayloadAndHeaders payloadAndHeaders = interceptor.intercept("SendMessage", "payload",
                Map.of("Authorization", "Bearer user-jwt"), null, null);

        assertThat(interceptor).isInstanceOf(LiteLlmA2AAuthInterceptor.class);
        assertThat(payloadAndHeaders.getHeaders())
                .doesNotContainKey("Authorization")
                .containsEntry("x-litellm-api-key", "Bearer sk-izzybot");
        verifyNoInteractions(tokenExchangeService, mcpFilterService);
    }

    /**
     * The sticky key outlives the registry: a chat attached to an agent that is gone after a restart is
     * released and told why, once, instead of failing every message.
     */
    @Test
    void anUnknownStickyAgentIsReleasedAndTheTurnFailsWithAMessageForTheUser() {
        when(agentRegistry.agent("network")).thenReturn(Optional.empty());
        when(stickyAgentService.deactivate(CHAT_ID)).thenReturn(Mono.empty());
        when(stickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(agentService.delegate(CHAT_ID, "network", "and yesterday?", "user-jwt"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(A2AAgentUnavailableException.class)
                        .hasMessage("The network agent is not available right now. Send your message again to continue without it."))
                .verify();

        verify(stickyAgentService).deactivate(CHAT_ID);
        verify(stickyAgentService).deactivateTask(CHAT_ID);
        verify(stickyAgentService, never()).getActiveTaskId(any());
    }

    @Test
    void anUnknownAgentStillFailsWithTheUsersMessageWhenTheReleaseFails() {
        when(agentRegistry.agent("network")).thenReturn(Optional.empty());
        AtomicBoolean taskReleased = new AtomicBoolean();
        when(stickyAgentService.deactivate(CHAT_ID)).thenReturn(Mono.error(new IllegalStateException("redis down")));
        when(stickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.fromRunnable(() -> taskReleased.set(true)));

        StepVerifier.create(agentService.delegate(CHAT_ID, "network", "and yesterday?", "user-jwt"))
                .expectError(A2AAgentUnavailableException.class)
                .verify();

        assertThat(taskReleased).isTrue();
    }

    @Test
    void nothingIsReleasedUntilTheTurnIsSubscribed() {
        Flux<String> turn = agentService.delegate(CHAT_ID, "network", "and yesterday?", "user-jwt");

        assertThat(turn).isNotNull();
        verifyNoInteractions(agentRegistry, stickyAgentService);
    }

    @Test
    void everyFirstMessageAsksForTheElicitationExtension() {
        Message continuing = agentService.firstMessage(CHAT_ID, "play", "task-1");
        Message starting = agentService.firstMessage(CHAT_ID, "new game Alex vs Sam", null);

        assertThat(continuing.extensions()).containsExactly(A2AElicitationExtension.URI);
        assertThat(continuing.taskId()).isEqualTo("task-1");
        assertThat(continuing.contextId()).isEqualTo(CHAT_ID.toString());
        assertThat(starting.extensions()).containsExactly(A2AElicitationExtension.URI);
        assertThat(starting.taskId()).isNull();
    }

    @Test
    void aCompletedReplyIsSavedToHistoryAsOneAssistantMessage() {
        when(stickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(turnOf(turn -> {
                    agentService.handleEvent(turn, update(new TaskStatusUpdateEvent("task-1",
                            new TaskStatus(TaskState.TASK_STATE_WORKING, agentMessage("Running query_sql…"), null), "context-1", null)));
                    agentService.handleEvent(turn, text("task-1", "Two devices "));
                    agentService.handleEvent(turn, text("task-1", "are offline."));
                    agentService.handleEvent(turn, status("task-1", TaskState.TASK_STATE_COMPLETED));
                }))
                .expectNext("", "Two devices ", "are offline.")
                .verifyComplete();

        assertThat(savedReplies()).containsExactly("Two devices are offline.");
    }

    @Test
    void aFailedReplyIsNotSaved() {
        when(stickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(turnOf(turn -> {
                    agentService.handleEvent(turn, text("task-1", "partial"));
                    agentService.handleEvent(turn, status("task-1", TaskState.TASK_STATE_FAILED));
                }))
                .expectNext("partial")
                .expectError(IllegalStateException.class)
                .verify();

        verifyNoInteractions(chatMessageService);
    }

    @Test
    void aReplyOfProgressOnlyIsNotSaved() {
        when(stickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(turnOf(turn -> {
                    agentService.handleEvent(turn, update(new TaskStatusUpdateEvent("task-1",
                            new TaskStatus(TaskState.TASK_STATE_WORKING, agentMessage("Running query_sql…"), null), "context-1", null)));
                    agentService.handleEvent(turn, status("task-1", TaskState.TASK_STATE_COMPLETED));
                }))
                .expectNext("")
                .verifyComplete();

        verifyNoInteractions(chatMessageService);
    }

    /**
     * A working status is progress, not answer text: it goes to the notification side channel and only
     * an empty chunk reaches the turn.
     */
    @Test
    void workingStatusBecomesAProgressNotificationNotAnswerText() {
        Message progress = agentMessage("Running query_sql…");

        StepVerifier.create(turnOf(turn -> {
                    agentService.handleEvent(turn, update(new TaskStatusUpdateEvent(
                            "task-1", new TaskStatus(TaskState.TASK_STATE_WORKING, progress, null), "context-1", null)));
                    turn.finish();
                }))
                .expectNext("")
                .verifyComplete();

        verify(notificationService).emitProgress(CHAT_ID, progress);
    }

    @Test
    void messageEventEmitsItsTextParts() {
        StepVerifier.create(turnOf(turn -> agentService.handleEvent(turn, new MessageEvent(agentMessage("response chunk")))))
                .expectNext("response chunk")
                .verifyComplete();

        assertThat(savedReplies()).containsExactly("response chunk");
    }

    @Test
    void terminalTaskEventEmitsItsArtifactsClearsTheTaskAndCompletes() {
        when(stickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(turnOf(turn -> agentService.handleEvent(turn,
                        new TaskEvent(task("task-1", TaskState.TASK_STATE_COMPLETED, List.of(artifact("final")))))))
                .expectNext("final")
                .verifyComplete();

        verify(stickyAgentService).deactivateTask(CHAT_ID);
    }

    @Test
    void inputRequiredTaskEventStoresTheTaskIdAndCompletes() {
        when(stickyAgentService.activateTask(CHAT_ID, "task-1")).thenReturn(Mono.empty());

        StepVerifier.create(turnOf(turn -> agentService.handleEvent(turn,
                        new TaskEvent(task("task-1", TaskState.TASK_STATE_INPUT_REQUIRED,
                                List.of(artifact("What is the title?")))))))
                .expectNext("What is the title?")
                .verifyComplete();

        verify(stickyAgentService).activateTask(CHAT_ID, "task-1");
        verifyNoInteractions(elicitationService);
    }

    @Test
    void inputRequiredStatusUpdateEmitsTheQuestionStoresTheTaskIdAndCompletes() {
        when(stickyAgentService.activateTask(CHAT_ID, "task-2")).thenReturn(Mono.empty());

        StepVerifier.create(turnOf(turn -> agentService.handleEvent(turn, update(new TaskStatusUpdateEvent("task-2",
                        new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, agentMessage("What priority?"), null),
                        "context-1", null)))))
                .expectNext("What priority?")
                .verifyComplete();

        verify(stickyAgentService).activateTask(CHAT_ID, "task-2");
        verifyNoInteractions(elicitationService);
    }

    /**
     * The reply before the prompt is saved before the prompt's own row, so history reads in the order
     * it happened, and the turn stays open for the answer.
     */
    @Test
    void aPromptIsRaisedAfterTheReplyBeforeItIsSavedAndTheTurnStaysOpen() {
        promptIsRaised(Mono.never());

        StepVerifier.create(turnOf(turn -> {
                    agentService.handleEvent(turn, text("task-3", "Face-off! "));
                    agentService.handleEvent(turn, prompt("task-3"));
                }))
                .expectNext("Face-off! ")
                .expectNoEvent(Duration.ofMillis(100))
                .thenCancel()
                .verify();

        ArgumentCaptor<ElicitationEvent> raised = ArgumentCaptor.forClass(ElicitationEvent.class);
        InOrder inOrder = inOrder(chatMessageService, elicitationService);
        inOrder.verify(chatMessageService).save(any(ChatMessage.class));
        inOrder.verify(elicitationService).emitElicitation(eq(CHAT_ID), eq(ELICITATION_ID), raised.capture());

        assertThat(raised.getValue()).isEqualTo(ElicitationEvent.form(CHAT_ID, ELICITATION_ID, "Alex, play or pass?",
                requestedSchema(), Map.of("feud/prompt", "play_or_pass")));
        assertThat(savedReplies()).containsExactly("Face-off!");
        verify(stickyAgentService).activateTask(CHAT_ID, "task-3");
    }

    @Test
    void anAcceptedAnswerContinuesTheTaskAndItsReplyStreamsIntoTheSameTurn() {
        promptIsRaised(Mono.just(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of("choice", "play"))));
        when(stickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());

        List<Message> sent = new ArrayList<>();

        StepVerifier.create(turnOf(turn -> {
                    turn.start(outgoing -> {
                        sent.add(outgoing);

                        if (sent.size() == 2) {
                            agentService.handleEvent(turn, text("task-3", "Alex plays."));
                            agentService.handleEvent(turn, status("task-3", TaskState.TASK_STATE_COMPLETED));
                        }
                    }, agentService.firstMessage(CHAT_ID, "buzz", "task-3"));

                    agentService.handleEvent(turn, text("task-3", "Face-off! "));
                    agentService.handleEvent(turn, prompt("task-3"));
                }))
                .expectNext("Face-off! ", "Alex plays.")
                .verifyComplete();

        assertThat(sent).hasSize(2);
        Message answer = sent.get(1);
        assertThat(answer.taskId()).isEqualTo("task-3");
        assertThat(answer.contextId()).isEqualTo(CHAT_ID.toString());
        assertThat(answer.extensions()).containsExactly(A2AElicitationExtension.URI);
        assertThat(answer.parts()).singleElement().isInstanceOf(DataPart.class);

        JsonNode data = jsonMapper.valueToTree(((DataPart) answer.parts().getFirst()).data());
        assertThat(data).isEqualTo(jsonMapper.readTree(
                "{\"elicitationResponse\":{\"action\":\"accept\",\"content\":{\"choice\":\"play\"}}}"));

        assertThat(savedReplies()).containsExactly("Face-off!", "Alex plays.");
    }

    /**
     * The continued task's snapshot carries the reply shown before the prompt as well as the new one;
     * the user sees each once.
     */
    @Test
    void aContinuedTasksSnapshotDoesNotShowTheReplyFromBeforeThePromptAgain() {
        promptIsRaised(Mono.just(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of("choice", "play"))));
        when(stickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());

        Artifact beforePrompt = Artifact.builder().artifactId("reply-1").parts(new TextPart("Face-off! ")).build();
        Artifact afterPrompt = Artifact.builder().artifactId("reply-2").parts(new TextPart("Alex plays.")).build();
        List<Message> sent = new ArrayList<>();

        StepVerifier.create(turnOf(turn -> {
                    turn.start(outgoing -> {
                        sent.add(outgoing);

                        if (sent.size() == 2) {
                            Task finished = task("task-3", TaskState.TASK_STATE_COMPLETED, List.of(beforePrompt, afterPrompt));
                            agentService.handleEvent(turn, new TaskEvent(finished));
                        }
                    }, agentService.firstMessage(CHAT_ID, "sunscreen", "task-3"));

                    agentService.handleEvent(turn, update(new TaskArtifactUpdateEvent("task-3", beforePrompt, "context-1", false, true, null)));
                    agentService.handleEvent(turn, prompt("task-3"));
                }))
                .expectNext("Face-off! ", "Alex plays.")
                .verifyComplete();

        assertThat(savedReplies()).containsExactly("Face-off!", "Alex plays.");
    }

    /**
     * An agent that keeps one artifact id for its reply replaces the earlier text; the new text is shown.
     */
    @Test
    void aReplyReplacedUnderTheSameArtifactIdIsShown() {
        promptIsRaised(Mono.just(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of("choice", "play"))));
        when(stickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());

        List<Message> sent = new ArrayList<>();

        StepVerifier.create(turnOf(turn -> {
                    turn.start(outgoing -> {
                        sent.add(outgoing);

                        if (sent.size() == 2) {
                            Task finished = task("task-3", TaskState.TASK_STATE_COMPLETED, List.of(artifact("Alex plays.")));
                            agentService.handleEvent(turn, new TaskEvent(finished));
                        }
                    }, agentService.firstMessage(CHAT_ID, "sunscreen", "task-3"));

                    agentService.handleEvent(turn, text("task-3", "Face-off! "));
                    agentService.handleEvent(turn, prompt("task-3"));
                }))
                .expectNext("Face-off! ", "Alex plays.")
                .verifyComplete();
    }

    /**
     * Nothing is sent: the task stays waiting and sticky, so the user's next typed message continues it.
     */
    @Test
    void aDeclinedPromptEndsTheTurnAndSendsNothing() {
        promptIsRaised(Mono.just(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, null)));

        List<Message> sent = new ArrayList<>();

        StepVerifier.create(turnOf(turn -> {
                    turn.start(sent::add, agentService.firstMessage(CHAT_ID, "buzz", "task-3"));
                    agentService.handleEvent(turn, prompt("task-3"));
                }))
                .verifyComplete();

        assertThat(sent).hasSize(1);
        verify(stickyAgentService).activateTask(CHAT_ID, "task-3");
        verify(stickyAgentService, never()).deactivateTask(any());
    }

    @Test
    void anAcceptedAnswerWithNothingToForwardEndsTheTurn() {
        promptIsRaised(Mono.just(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, null)));

        List<Message> sent = new ArrayList<>();

        StepVerifier.create(turnOf(turn -> {
                    turn.start(sent::add, agentService.firstMessage(CHAT_ID, "buzz", "task-3"));
                    agentService.handleEvent(turn, prompt("task-3"));
                }))
                .verifyComplete();

        assertThat(sent).hasSize(1);
    }

    @Test
    void anAnswerThatCannotBeSentFailsTheTurn() {
        promptIsRaised(Mono.just(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of("choice", "play"))));

        List<Message> sent = new ArrayList<>();

        StepVerifier.create(turnOf(turn -> {
                    turn.start(outgoing -> {
                        sent.add(outgoing);

                        if (sent.size() == 2) {
                            throw new IllegalStateException("connection refused");
                        }
                    }, agentService.firstMessage(CHAT_ID, "buzz", "task-3"));

                    agentService.handleEvent(turn, prompt("task-3"));
                }))
                .expectErrorMessage("connection refused")
                .verify();
    }

    /**
     * A prompt that cannot be raised ends the turn as it would without one, so the user can still type.
     */
    @Test
    void aPromptThatCannotBeRaisedEndsTheTurnNormally() {
        when(stickyAgentService.activateTask(CHAT_ID, "task-3")).thenReturn(Mono.empty());
        when(elicitationService.prepareElicitation(CHAT_ID)).thenReturn(new ElicitationService.ElicitationHandle(ELICITATION_ID));
        doThrow(new IllegalStateException("unwritable")).when(elicitationService)
                .emitElicitation(eq(CHAT_ID), eq(ELICITATION_ID), any(ElicitationEvent.class));

        StepVerifier.create(turnOf(turn -> agentService.handleEvent(turn, prompt("task-3"))))
                .verifyComplete();

        verify(elicitationService, never()).awaitResultAsync(any(), any());
    }

    @Test
    void aPromptOnATaskEventIsRaisedAfterItsArtifacts() {
        promptIsRaised(Mono.never());

        Task waiting = Task.builder()
                .id("task-3")
                .contextId("context-1")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, promptMessage(), null))
                .artifacts(List.of(artifact("Face-off! ")))
                .build();

        StepVerifier.create(turnOf(turn -> agentService.handleEvent(turn, new TaskEvent(waiting))))
                .expectNext("Face-off! ")
                .expectNoEvent(Duration.ofMillis(100))
                .thenCancel()
                .verify();

        verify(elicitationService).emitElicitation(eq(CHAT_ID), eq(ELICITATION_ID), any(ElicitationEvent.class));
    }

    /**
     * An agent may report the same waiting state twice, as a task snapshot and as a status update; the
     * user must be asked once.
     */
    @Test
    void aSecondPromptWhileOneWaitsIsIgnored() {
        promptIsRaised(Mono.never());

        Task waiting = Task.builder()
                .id("task-3")
                .contextId("context-1")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, promptMessage(), null))
                .build();

        StepVerifier.create(turnOf(turn -> {
                    agentService.handleEvent(turn, prompt("task-3"));
                    agentService.handleEvent(turn, new TaskEvent(waiting));
                }))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(100))
                .thenCancel()
                .verify();

        verify(elicitationService).prepareElicitation(CHAT_ID);
        verify(elicitationService).emitElicitation(eq(CHAT_ID), eq(ELICITATION_ID), any(ElicitationEvent.class));
        verify(elicitationService).awaitResultAsync(CHAT_ID, ELICITATION_ID);
    }

    /**
     * A cancelled turn saves nothing and asks nothing, even when the agent's events arrive after the
     * cancel.
     */
    @Test
    void eventsAfterACancelAreIgnored() {
        when(stickyAgentService.activateTask(CHAT_ID, "task-3")).thenReturn(Mono.empty());
        when(stickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());

        AtomicReference<A2AAgentService.DelegatedTurn> cancelled = new AtomicReference<>();

        StepVerifier.create(turnOf(cancelled::set))
                .expectSubscription()
                .thenCancel()
                .verify();

        agentService.handleEvent(cancelled.get(), text("task-3", "late text"));
        agentService.handleEvent(cancelled.get(), prompt("task-3"));
        agentService.handleEvent(cancelled.get(), status("task-3", TaskState.TASK_STATE_COMPLETED));

        verifyNoInteractions(chatMessageService, elicitationService);
    }

    @Test
    void cancellingTheTurnWhileAPromptWaitsDropsTheWait() {
        PublisherProbe<McpSchema.ElicitResult> answer = PublisherProbe.of(Mono.never());
        promptIsRaised(answer.mono());

        StepVerifier.create(turnOf(turn -> agentService.handleEvent(turn, prompt("task-3"))))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(100))
                .thenCancel()
                .verify();

        answer.assertWasSubscribed();
        answer.assertWasCancelled();
    }

    /**
     * A person deciding is never timed out by the agent timeout; once the answer goes out, an agent that
     * goes quiet is.
     */
    @Test
    void theAgentTimeoutIsPausedWhileAPromptWaitsAndResumesOnceTheAnswerIsSent() {
        Sinks.One<McpSchema.ElicitResult> answer = Sinks.one();
        promptIsRaised(answer.asMono());

        StepVerifier.withVirtualTime(() -> turnOf(turn -> {
                    turn.start(_ -> {
                    }, agentService.firstMessage(CHAT_ID, "buzz", "task-3"));
                    agentService.handleEvent(turn, prompt("task-3"));
                }))
                .expectSubscription()
                .expectNoEvent(AGENT_TIMEOUT.multipliedBy(20))
                .then(() -> answer.tryEmitValue(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
                        Map.of("choice", "play"))))
                .thenAwait(AGENT_TIMEOUT)
                .expectError(TimeoutException.class)
                .verify();
    }

    @Test
    void anAgentThatGoesQuietTimesTheTurnOut() {
        StepVerifier.withVirtualTime(() -> turnOf(turn ->
                        turn.start(_ -> {
                        }, agentService.firstMessage(CHAT_ID, "buzz", null))))
                .expectSubscription()
                .thenAwait(AGENT_TIMEOUT)
                .expectError(TimeoutException.class)
                .verify();

        verifyNoInteractions(chatMessageService);
    }
}
