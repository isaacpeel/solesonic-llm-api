package com.solesonic.service.a2a;

import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.config.a2a.A2AAuthInterceptor;
import com.solesonic.config.a2a.A2AClientProperties;
import io.a2a.client.MessageEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
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
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class A2AAgentServiceTest {

    @Mock
    private A2AAgentRegistry agentRegistry;

    @Mock
    private A2AAuthInterceptor a2aAuthInterceptor;

    @Mock
    private com.solesonic.service.chat.ElicitationService elicitationService;

    @Mock
    private A2AStickyAgentService stickyAgentService;

    private A2AAgentService agentService;

    private static final UUID CHAT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        A2AClientProperties properties = new A2AClientProperties(false, 5, null);

        agentService = new A2AAgentService(agentRegistry, a2aAuthInterceptor, elicitationService,
                Optional.of(stickyAgentService), properties);
    }

    @Test
    void delegateThrowsWhenAgentUnknown() {
        when(agentRegistry.getCard("missing"))
                .thenThrow(new IllegalArgumentException("Unknown A2A agent: missing"));

        Flux<String> flux = agentService.delegate(UUID.randomUUID(), "missing", "hi", "test-token");

        StepVerifier.create(flux)
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void taskArtifactUpdateEventEmitsTextChunk() {
        when(stickyAgentService.deactivateTask(any())).thenReturn(Mono.empty());

        Task workingTask = new Task(
                "task-1", "context-1",
                new TaskStatus(TaskState.WORKING), null, null, null);

        StepVerifier.create(captureFromEvents(sink -> {
            TaskArtifactUpdateEvent artifactEvent = new TaskArtifactUpdateEvent(
                    "task-1",
                    new Artifact("artifact-1", null, null, List.<Part<?>>of(new TextPart("hello")), null, null),
                    "context-1",
                    false,
                    false,
                    null);

            agentService.handleEvent(CHAT_ID, new TaskUpdateEvent(workingTask, artifactEvent), sink);

            TaskStatusUpdateEvent statusEvent = new TaskStatusUpdateEvent(
                    "task-1",
                    new TaskStatus(TaskState.COMPLETED),
                    "context-1",
                    true,
                    null);

            agentService.handleEvent(CHAT_ID, new TaskUpdateEvent(workingTask, statusEvent), sink);
        }))
                .expectNext("hello")
                .verifyComplete();
    }

    @Test
    void messageEventEmitsTextParts() {
        StepVerifier.create(captureFromEvents(sink -> {
            Message message = new Message.Builder()
                    .role(Message.Role.AGENT)
                    .parts(new TextPart("response chunk"))
                    .messageId("msg-1")
                    .build();

            agentService.handleEvent(CHAT_ID, new MessageEvent(message), sink);
            sink.complete();
        }))
                .expectNext("response chunk")
                .verifyComplete();
    }

    @Test
    void terminalTaskEventEmitsArtifactsAndCompletes() {
        when(stickyAgentService.deactivateTask(any())).thenReturn(Mono.empty());

        StepVerifier.create(captureFromEvents(sink -> {
            Artifact artifact = new Artifact("artifact-1", null, null, List.<Part<?>>of(new TextPart("final")), null, null);
            Task task = new Task(
                    "task-1",
                    "context-1",
                    new TaskStatus(TaskState.COMPLETED),
                    List.of(artifact),
                    null,
                    null);

            agentService.handleEvent(CHAT_ID, new TaskEvent(task), sink);
        }))
                .expectNext("final")
                .verifyComplete();
    }

    @Test
    void failedTaskStatusErrorsTheSink() {
        when(stickyAgentService.deactivateTask(any())).thenReturn(Mono.empty());

        Task workingTask = new Task(
                "task-1", "context-1",
                new TaskStatus(TaskState.WORKING), null, null, null);

        StepVerifier.create(captureFromEvents(sink -> {
            TaskStatusUpdateEvent failedEvent = new TaskStatusUpdateEvent(
                    "task-1",
                    new TaskStatus(TaskState.FAILED),
                    "context-1",
                    true,
                    null);

            agentService.handleEvent(CHAT_ID, new TaskUpdateEvent(workingTask, failedEvent), sink);
        }))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void inputRequiredTaskEventEmitsArtifactsStoresTaskIdAndCompletes() {
        when(stickyAgentService.activateTask(eq(CHAT_ID), eq("task-1"))).thenReturn(Mono.empty());

        Artifact artifact = new Artifact("a-1", null, null,
                List.<Part<?>>of(new TextPart("What is the title?")), null, null);
        Task task = new Task(
                "task-1",
                "context-1",
                new TaskStatus(TaskState.INPUT_REQUIRED),
                List.of(artifact),
                null,
                null);

        StepVerifier.create(captureFromEvents(sink ->
                agentService.handleEvent(CHAT_ID, new TaskEvent(task), sink)
        ))
                .expectNext("What is the title?")
                .verifyComplete();

        verify(stickyAgentService).activateTask(CHAT_ID, "task-1");
    }

    @Test
    void inputRequiredStatusUpdateEmitsMessagePartsStoresTaskIdAndCompletes() {
        when(stickyAgentService.activateTask(eq(CHAT_ID), eq("task-2"))).thenReturn(Mono.empty());

        Task workingTask = new Task(
                "task-2", "context-1",
                new TaskStatus(TaskState.WORKING), null, null, null);

        Message agentQuestion = new Message.Builder()
                .role(Message.Role.AGENT)
                .parts(new TextPart("What priority?"))
                .messageId("msg-q")
                .build();

        TaskStatusUpdateEvent inputRequiredEvent = new TaskStatusUpdateEvent(
                "task-2",
                new TaskStatus(TaskState.INPUT_REQUIRED, agentQuestion, null),
                "context-1",
                false,
                null);

        StepVerifier.create(captureFromEvents(sink ->
                agentService.handleEvent(CHAT_ID, new TaskUpdateEvent(workingTask, inputRequiredEvent), sink)
        ))
                .expectNext("What priority?")
                .verifyComplete();

        verify(stickyAgentService).activateTask(CHAT_ID, "task-2");
    }

    @Test
    void finalTaskEventClearsActiveTask() {
        when(stickyAgentService.deactivateTask(eq(CHAT_ID))).thenReturn(Mono.empty());

        Task completedTask = new Task(
                "task-3", "context-1",
                new TaskStatus(TaskState.COMPLETED), null, null, null);

        StepVerifier.create(captureFromEvents(sink ->
                agentService.handleEvent(CHAT_ID, new TaskEvent(completedTask), sink)
        ))
                .verifyComplete();

        verify(stickyAgentService).deactivateTask(CHAT_ID);
    }

    private Flux<String> captureFromEvents(java.util.function.Consumer<FluxSink<String>> emitter) {
        return Flux.create(emitter);
    }

}
