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
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class A2AAgentServiceTest {

    @Mock
    private A2AAgentRegistry agentRegistry;

    @Mock
    private A2AAuthInterceptor a2aAuthInterceptor;

    @Mock
    private com.solesonic.service.chat.ElicitationService elicitationService;

    private A2AAgentService agentService;

    private static final UUID CHAT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        A2AClientProperties properties = new A2AClientProperties();
        properties.setTimeoutSeconds(5);

        agentService = new A2AAgentService(agentRegistry, a2aAuthInterceptor, elicitationService, properties);
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

    private Flux<String> captureFromEvents(java.util.function.Consumer<FluxSink<String>> emitter) {
        return Flux.create(emitter);
    }

}
