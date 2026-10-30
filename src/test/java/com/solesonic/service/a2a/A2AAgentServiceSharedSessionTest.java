package com.solesonic.service.a2a;

import com.solesonic.config.JacksonConfig;
import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.config.a2a.A2AClientProperties;
import com.solesonic.exception.a2a.A2AAgentUnavailableException;
import com.solesonic.mcp.client.TokenExchangeService;
import com.solesonic.model.a2a.A2AElicitationExtension;
import com.solesonic.model.a2a.A2AParticipantMessage;
import com.solesonic.model.a2a.A2AParticipantsExtension;
import com.solesonic.model.a2a.A2APrompt;
import com.solesonic.model.a2a.A2ASoundExtension;
import com.solesonic.model.a2a.A2ATurnState;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.security.McpFilterService;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.chat.PendingMessageMetadata;
import com.solesonic.service.chat.events.ElicitationService;
import com.solesonic.service.chat.events.NotificationService;
import org.a2aproject.sdk.client.TaskUpdateEvent;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A shared session's delegation: the message names its sender and the participants, every reply's
 * turn is reported, and the question the agent ends on is handed on rather than parked.
 */
@ExtendWith(MockitoExtension.class)
class A2AAgentServiceSharedSessionTest {

    private static final UUID CHAT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final List<String> PARTICIPANTS = List.of("Alex", "Sam");

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

    @Mock
    private A2ASoundService soundService;

    private A2AAgentService agentService;

    private final List<A2ATurnState> turns = new ArrayList<>();
    private final List<A2APrompt> prompts = new ArrayList<>();
    private final List<String> promptTasks = new ArrayList<>();

    private final A2ASessionTurnListener listener = new A2ASessionTurnListener() {
        @Override
        public void turn(A2ATurnState turnState) {
            turns.add(turnState);
        }

        @Override
        public void prompt(String taskId, A2APrompt prompt) {
            promptTasks.add(taskId);
            prompts.add(prompt);
        }
    };

    @BeforeEach
    void setUp() {
        A2AClientProperties properties = new A2AClientProperties(false, 5, "http://agents",
                new A2AClientProperties.Litellm("http://litellm:4000", "sk-izzybot"),
                new A2AClientProperties.Sound(DataSize.ofKilobytes(1), Duration.ofDays(7)));

        agentService = new A2AAgentService(agentRegistry, tokenExchangeService, mcpFilterService,
                notificationService, stickyAgentService, chatMessageService, elicitationService,
                new JacksonConfig().jsonMapper(), properties, soundService, new PendingMessageMetadata());
    }

    private Flux<String> sharedTurnOf(Consumer<A2AAgentService.DelegatedTurn> script) {
        return Flux.create(sink -> {
            A2AAgentService.DelegatedTurn turn = agentService.turn(CHAT_ID, sink, new A2AAgentService.SharedMode(PARTICIPANTS, listener));
            sink.onDispose(turn::dispose);
            script.accept(turn);
        });
    }

    private static Artifact reply(String text, Map<String, Object> turn) {
        return Artifact.builder()
                .artifactId("reply")
                .parts(new TextPart(text))
                .metadata(Map.of(A2AParticipantsExtension.TURN_KEY, turn))
                .build();
    }

    @SuppressWarnings("all")
    private static Task task(String taskId, TaskState state) {
        return Task.builder().id(taskId).contextId("context-1").status(new TaskStatus(state)).build();
    }

    private static TaskUpdateEvent artifactUpdate(String taskId, Artifact artifact) {
        return new TaskUpdateEvent(task(taskId, TaskState.TASK_STATE_WORKING),
                new TaskArtifactUpdateEvent(taskId, artifact, "context-1", false, true, null));
    }

    private static TaskUpdateEvent statusUpdate(String taskId, TaskStatus status) {
        return new TaskUpdateEvent(task(taskId, TaskState.TASK_STATE_WORKING),
                new TaskStatusUpdateEvent(taskId, status, "context-1", null));
    }

    private static Message promptMessage() {
        return Message.builder()
                .role(Message.Role.ROLE_AGENT)
                .messageId("prompt-1")
                .parts(new DataPart(Map.of("elicitation", Map.of(
                        "mode", "form",
                        "message", "Your call",
                        "requestedSchema", Map.of("type", "object", "properties", Map.of("choice", Map.of("type", "string"))),
                        "_meta", Map.of("feud/prompt", "play_or_pass", "solesonic/forParticipants", List.of("Alex"))))))
                .build();
    }

    @Test
    void aSharedMessageNamesItsSenderAndTheParticipants() {
        Message message = agentService.sharedMessage(CHAT_ID, A2AParticipantMessage.typed("towel", "Sam", PARTICIPANTS), "task-1");

        List<String> extensions = List.of(A2AElicitationExtension.URI, A2ASoundExtension.URI, A2AParticipantsExtension.URI);

        assertThat(message.extensions()).isEqualTo(extensions);
        assertThat(message.taskId()).isEqualTo("task-1");
        assertThat(message.metadata())
                .containsEntry(A2AParticipantsExtension.EXTENSIONS_KEY, extensions)
                .containsEntry(A2AParticipantsExtension.PARTICIPANT_KEY, Map.of("name", "Sam"))
                .containsEntry(A2AParticipantsExtension.PARTICIPANTS_KEY, PARTICIPANTS);
        assertThat(message.parts()).singleElement().isInstanceOfSatisfying(TextPart.class,
                textPart -> assertThat(textPart.text()).isEqualTo("towel"));
    }

    @Test
    void anAnswerIsSentAsTheElicitationResponsePart() {
        Message message = agentService.sharedMessage(CHAT_ID, A2AParticipantMessage.answer(Map.of("choice", "play"), "Alex", PARTICIPANTS), null);

        assertThat(message.parts()).singleElement().isInstanceOfSatisfying(DataPart.class, dataPart ->
                assertThat(dataPart.data()).isEqualTo(Map.of("elicitationResponse", Map.of("action", "accept", "content", Map.of("choice", "play")))));
        assertThat(message.metadata()).containsEntry(A2AParticipantsExtension.PARTICIPANT_KEY, Map.of("name", "Alex"));
    }

    @Test
    void theTurnEachReplyReportsIsHandedOn() {
        when(stickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(sharedTurnOf(turn -> {
                    agentService.handleEvent(turn, artifactUpdate("task-1",
                            reply("Sam, your answer?", Map.of("participants", List.of("sam", "Kim"), "next", "answer", "over", false))));
                    agentService.handleEvent(turn, statusUpdate("task-1", new TaskStatus(TaskState.TASK_STATE_COMPLETED)));
                }))
                .expectNext("Sam, your answer?")
                .verifyComplete();

        assertThat(turns).containsExactly(new A2ATurnState(List.of("Sam"), "answer", false, List.of()));
    }

    /**
     * The participant the question is for may not answer for hours: the turn ends, the task stays sticky
     * for the answer, and nothing waits on an elicitation.
     */
    @Test
    void aQuestionIsHandedOnAndTheTurnEnds() {
        when(stickyAgentService.activateTask(CHAT_ID, "task-2")).thenReturn(Mono.empty());

        StepVerifier.create(sharedTurnOf(turn -> {
                    agentService.handleEvent(turn, artifactUpdate("task-2",
                            reply("Alex, play or pass?", Map.of("participants", List.of("Alex"), "next", "choice"))));
                    agentService.handleEvent(turn, statusUpdate("task-2",
                            new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, promptMessage(), null)));
                }))
                .expectNext("Alex, play or pass?")
                .verifyComplete();

        assertThat(promptTasks).containsExactly("task-2");
        assertThat(prompts).singleElement().satisfies(prompt -> assertThat(prompt.message()).isEqualTo("Your call"));
        assertThat(turns).containsExactly(new A2ATurnState(List.of("Alex"), "choice", false, List.of()));
        verify(stickyAgentService).activateTask(CHAT_ID, "task-2");
        verifyNoInteractions(elicitationService);

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageService).save(saved.capture());
        assertThat(saved.getValue().getMessage()).isEqualTo("Alex, play or pass?");
    }

    @Test
    void aTurnThatDoesNotHoldUpIsNotReported() {
        when(stickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(sharedTurnOf(turn -> {
                    agentService.handleEvent(turn, artifactUpdate("task-1",
                            reply("Hello", Map.of("participants", List.of("Alex"), "next", "DROP TABLE"))));
                    agentService.handleEvent(turn, statusUpdate("task-1", new TaskStatus(TaskState.TASK_STATE_COMPLETED)));
                }))
                .expectNext("Hello")
                .verifyComplete();

        assertThat(turns).isEmpty();
    }

    /**
     * The session names its agent; there is no sticky agent to release when it is gone.
     */
    @Test
    void anUnknownSessionAgentFailsWithoutReleasingAnything() {
        when(agentRegistry.agent("feud")).thenReturn(Optional.empty());

        StepVerifier.create(agentService.delegateShared(CHAT_ID, "feud", A2AParticipantMessage.typed("towel", "Alex", PARTICIPANTS), "user-jwt", listener))
                .expectError(A2AAgentUnavailableException.class)
                .verify();

        verify(stickyAgentService, never()).deactivate(any());
        verify(stickyAgentService, never()).deactivateTask(any());
    }
}
