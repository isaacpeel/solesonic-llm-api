package com.solesonic.service.a2a;

import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.config.a2a.A2AAuthInterceptor;
import com.solesonic.config.a2a.A2AClientProperties;
import com.solesonic.config.a2a.LiteLlmA2AAuthInterceptor;
import com.solesonic.exception.a2a.A2AAgentUnavailableException;
import com.solesonic.mcp.client.TokenExchangeService;
import com.solesonic.model.a2a.A2AAgentSource;
import com.solesonic.model.security.McpFilterService;
import com.solesonic.service.chat.events.NotificationService;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor;
import org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders;
import org.a2aproject.sdk.spec.Artifact;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class A2AAgentServiceTest {

    private static final UUID CHAT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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

    private A2AAgentService agentService;

    @BeforeEach
    void setUp() {
        A2AClientProperties properties = new A2AClientProperties(false, 5, "http://agents",
                new A2AClientProperties.Litellm("http://litellm:4000", "sk-izzybot"));

        agentService = new A2AAgentService(agentRegistry, tokenExchangeService, mcpFilterService,
                notificationService, stickyAgentService, properties);
    }

    private Flux<String> captureFromEvents(Consumer<FluxSink<String>> emitter) {
        return Flux.create(emitter);
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
    @SuppressWarnings("all")
    void nothingIsReleasedUntilTheTurnIsSubscribed() {
        agentService.delegate(CHAT_ID, "network", "and yesterday?", "user-jwt");

        verifyNoInteractions(agentRegistry, stickyAgentService);
    }

    @Test
    void artifactUpdateEmitsTheAnswerText() {
        when(stickyAgentService.deactivateTask(any())).thenReturn(Mono.empty());

        Task workingTask = task("task-1", TaskState.TASK_STATE_WORKING, null);

        StepVerifier.create(captureFromEvents(sink -> {
                    TaskArtifactUpdateEvent artifactEvent = new TaskArtifactUpdateEvent(
                            "task-1", artifact("hello"), "context-1", false, true, null);

                    agentService.handleEvent(CHAT_ID, new TaskUpdateEvent(workingTask, artifactEvent), sink);

                    TaskStatusUpdateEvent completedEvent = new TaskStatusUpdateEvent(
                            "task-1", new TaskStatus(TaskState.TASK_STATE_COMPLETED), "context-1", null);

                    agentService.handleEvent(CHAT_ID, new TaskUpdateEvent(workingTask, completedEvent), sink);
                }))
                .expectNext("hello")
                .verifyComplete();
    }

    /**
     * A working status is progress, not answer text: it goes to the notification side channel and only
     * an empty chunk reaches the turn.
     */
    @Test
    void workingStatusBecomesAProgressNotificationNotAnswerText() {
        Task workingTask = task("task-1", TaskState.TASK_STATE_WORKING, null);
        Message progress = agentMessage("Running query_sql…");

        StepVerifier.create(captureFromEvents(sink -> {
                    TaskStatusUpdateEvent workingEvent = new TaskStatusUpdateEvent(
                            "task-1", new TaskStatus(TaskState.TASK_STATE_WORKING, progress, null), "context-1", null);

                    agentService.handleEvent(CHAT_ID, new TaskUpdateEvent(workingTask, workingEvent), sink);
                    sink.complete();
                }))
                .expectNext("")
                .verifyComplete();

        verify(notificationService).emitProgress(CHAT_ID, progress);
    }

    @Test
    void messageEventEmitsItsTextParts() {
        StepVerifier.create(captureFromEvents(sink ->
                        agentService.handleEvent(CHAT_ID, new MessageEvent(agentMessage("response chunk")), sink)))
                .expectNext("response chunk")
                .verifyComplete();
    }

    @Test
    void terminalTaskEventEmitsItsArtifactsClearsTheTaskAndCompletes() {
        when(stickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(captureFromEvents(sink -> agentService.handleEvent(CHAT_ID,
                        new TaskEvent(task("task-1", TaskState.TASK_STATE_COMPLETED, List.of(artifact("final")))), sink)))
                .expectNext("final")
                .verifyComplete();

        verify(stickyAgentService).deactivateTask(CHAT_ID);
    }

    @Test
    void failedTaskStatusErrorsTheTurn() {
        when(stickyAgentService.deactivateTask(any())).thenReturn(Mono.empty());

        Task workingTask = task("task-1", TaskState.TASK_STATE_WORKING, null);

        StepVerifier.create(captureFromEvents(sink -> {
                    TaskStatusUpdateEvent failedEvent = new TaskStatusUpdateEvent(
                            "task-1", new TaskStatus(TaskState.TASK_STATE_FAILED), "context-1", null);

                    agentService.handleEvent(CHAT_ID, new TaskUpdateEvent(workingTask, failedEvent), sink);
                }))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void inputRequiredTaskEventStoresTheTaskIdAndCompletes() {
        when(stickyAgentService.activateTask(CHAT_ID, "task-1")).thenReturn(Mono.empty());

        StepVerifier.create(captureFromEvents(sink -> agentService.handleEvent(CHAT_ID,
                        new TaskEvent(task("task-1", TaskState.TASK_STATE_INPUT_REQUIRED,
                                List.of(artifact("What is the title?")))), sink)))
                .expectNext("What is the title?")
                .verifyComplete();

        verify(stickyAgentService).activateTask(CHAT_ID, "task-1");
    }

    @Test
    void inputRequiredStatusUpdateEmitsTheQuestionStoresTheTaskIdAndCompletes() {
        when(stickyAgentService.activateTask(CHAT_ID, "task-2")).thenReturn(Mono.empty());

        Task workingTask = task("task-2", TaskState.TASK_STATE_WORKING, null);

        StepVerifier.create(captureFromEvents(sink -> {
                    TaskStatusUpdateEvent inputRequiredEvent = new TaskStatusUpdateEvent("task-2",
                            new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, agentMessage("What priority?"), null),
                            "context-1", null);

                    agentService.handleEvent(CHAT_ID, new TaskUpdateEvent(workingTask, inputRequiredEvent), sink);
                }))
                .expectNext("What priority?")
                .verifyComplete();

        verify(stickyAgentService).activateTask(CHAT_ID, "task-2");
    }
}
