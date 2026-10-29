package com.solesonic.service.a2a;

import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.config.a2a.A2AAuthInterceptor;
import com.solesonic.config.a2a.A2AClientProperties;
import com.solesonic.config.a2a.LiteLlmA2AAuthInterceptor;
import com.solesonic.exception.a2a.A2AAgentUnavailableException;
import com.solesonic.mcp.client.TokenExchangeService;
import com.solesonic.model.a2a.A2AAgentSource;
import com.solesonic.model.a2a.A2AElicitationExtension;
import com.solesonic.model.a2a.A2AParticipantMessage;
import com.solesonic.model.a2a.A2AParticipantsExtension;
import com.solesonic.model.a2a.A2APrompt;
import com.solesonic.model.a2a.A2ASound;
import com.solesonic.model.a2a.A2ASoundExtension;
import com.solesonic.model.a2a.RegisteredAgent;
import com.solesonic.model.chat.ElicitationEvent;
import com.solesonic.model.chat.SoundCue;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.security.McpFilterService;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.chat.PendingMessageMetadata;
import com.solesonic.service.chat.events.ElicitationService;
import com.solesonic.service.chat.events.NotificationService;
import io.modelcontextprotocol.spec.McpSchema;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.UpdateEvent;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class A2AAgentService {
    private static final Logger log = LoggerFactory.getLogger(A2AAgentService.class);

    private final A2AAgentRegistry agentRegistry;
    private final TokenExchangeService tokenExchangeService;
    private final McpFilterService mcpFilterService;
    private final NotificationService notificationService;
    private final A2AStickyAgentService a2aStickyAgentService;
    private final ChatMessageService chatMessageService;
    private final ElicitationService elicitationService;
    private final JsonMapper jsonMapper;
    private final LiteLlmA2AAuthInterceptor liteLlmAuthInterceptor;
    private final Duration timeout;
    private final A2ASoundService soundService;
    private final PendingMessageMetadata pendingMessageMetadata;
    private final long maxSoundBytes;

    public A2AAgentService(A2AAgentRegistry agentRegistry,
                           TokenExchangeService tokenExchangeService,
                           McpFilterService mcpFilterService,
                           NotificationService notificationService,
                           A2AStickyAgentService a2aStickyAgentService,
                           ChatMessageService chatMessageService,
                           ElicitationService elicitationService,
                           JsonMapper jsonMapper,
                           A2AClientProperties properties,
                           A2ASoundService soundService,
                           PendingMessageMetadata pendingMessageMetadata) {
        this.agentRegistry = agentRegistry;
        this.tokenExchangeService = tokenExchangeService;
        this.mcpFilterService = mcpFilterService;
        this.notificationService = notificationService;
        this.a2aStickyAgentService = a2aStickyAgentService;
        this.chatMessageService = chatMessageService;
        this.elicitationService = elicitationService;
        this.jsonMapper = jsonMapper;
        this.liteLlmAuthInterceptor = new LiteLlmA2AAuthInterceptor(properties.litellm().apiKey());
        this.timeout = Duration.ofSeconds(properties.timeoutSeconds());
        this.soundService = soundService;
        this.pendingMessageMetadata = pendingMessageMetadata;
        this.maxSoundBytes = properties.sound().maxBytes().toBytes();
    }

    /**
     * @param userToken used only for an agent-host agent; a LiteLLM agent is called with the virtual key
     *                  alone, so the user's token never reaches the gateway.
     */
    public Flux<String> delegate(UUID chatId, String agentName, String message, String userToken) {
        return Flux.defer(() -> agentRegistry.agent(agentName)
                .map(registeredAgent -> delegate(chatId, registeredAgent,
                        activeTaskId -> firstMessage(chatId, message, activeTaskId), userToken, null))
                .orElseGet(() -> releaseUnavailableAgent(chatId, agentName)));
    }

    /**
     * A message in a session several users share. Unlike {@link #delegate(UUID, String, String, String)},
     * the message names its sender and the participants, every reply's turn is reported to
     * {@code listener}, and a question the agent ends on is handed to it rather than parked: the
     * participant it is for may not answer for hours. The session names its agent, so nothing here is
     * sticky but the A2A task.
     */
    public Flux<String> delegateShared(UUID chatId,
                                       String agentName,
                                       A2AParticipantMessage participantMessage,
                                       String userToken,
                                       A2ASessionTurnListener listener) {
        return Flux.defer(() -> agentRegistry.agent(agentName)
                .map(registeredAgent -> delegate(chatId, registeredAgent,
                        activeTaskId -> sharedMessage(chatId, participantMessage, activeTaskId), userToken,
                        new SharedMode(participantMessage.participants(), listener)))
                .orElseGet(() -> Flux.error(new A2AAgentUnavailableException(agentName))));
    }

    private Flux<String> delegate(UUID chatId,
                                  RegisteredAgent registeredAgent,
                                  Function<String, Message> firstMessage,
                                  String userToken,
                                  @Nullable SharedMode sharedMode) {
        return a2aStickyAgentService.getActiveTaskId(chatId)
                .flatMapMany(activeTaskId -> Flux.create(sink -> {
                    DelegatedTurn turn = turn(chatId, sink, sharedMode);
                    BiConsumer<ClientEvent, AgentCard> consumer = (event, _) -> handleEvent(turn, event);

                    JSONRPCTransportConfigBuilder jsonrpcTransportConfigBuilder = new JSONRPCTransportConfigBuilder()
                            .addInterceptor(interceptor(registeredAgent.source(), userToken));

                    Client client = Client.builder(registeredAgent.agentCard())
                            .withTransport(JSONRPCTransport.class, jsonrpcTransportConfigBuilder)
                            .addConsumer(consumer)
                            .streamingErrorHandler(turn::fail)
                            .build();

                    sink.onDispose(() -> {
                        turn.dispose();
                        client.close();
                    });

                    activeTaskId.ifPresent(s -> log.debug("Continuing A2A task '{}' for chat {}", s, chatId));

                    turn.start(outgoing -> client.sendMessage(outgoing, null),
                            firstMessage.apply(activeTaskId.orElse(null)));
                }));
    }

    Message firstMessage(UUID chatId, String message, @Nullable String activeTaskId) {
        return Message.builder()
                .role(Message.Role.ROLE_USER)
                .contextId(chatId.toString())
                .taskId(activeTaskId)
                .messageId(UUID.randomUUID().toString())
                .extensions(List.of(A2AElicitationExtension.URI, A2ASoundExtension.URI))
                .parts(new TextPart(message))
                .build();
    }

    /**
     * Named in {@code solesonic/extensions} as well as {@code extensions}, because LiteLLM's gateway
     * may drop the latter and the participants extension is how the agent knows who is speaking.
     */
    Message sharedMessage(UUID chatId, A2AParticipantMessage participantMessage, @Nullable String activeTaskId) {
        List<String> extensions = List.of(A2AElicitationExtension.URI, A2ASoundExtension.URI, A2AParticipantsExtension.URI);

        Part<?> part = participantMessage.isAnswer()
                ? A2AElicitationExtension.answerPart(participantMessage.answerContent())
                : new TextPart(participantMessage.text());

        return Message.builder()
                .role(Message.Role.ROLE_USER)
                .contextId(chatId.toString())
                .taskId(activeTaskId)
                .messageId(UUID.randomUUID().toString())
                .extensions(extensions)
                .metadata(A2AParticipantsExtension.metadata(participantMessage.senderName(), participantMessage.participants(), extensions))
                .parts(part)
                .build();
    }

    DelegatedTurn turn(UUID chatId, FluxSink<String> sink) {
        return turn(chatId, sink, null);
    }

    DelegatedTurn turn(UUID chatId, FluxSink<String> sink, @Nullable SharedMode sharedMode) {
        return new DelegatedTurn(chatId, sink, sharedMode);
    }

    /**
     * How a shared session's turn differs from a conversation's: who takes part, and where the turn
     * and the question it ends on are reported.
     */
    record SharedMode(List<String> participants, A2ASessionTurnListener listener) {
    }

    ClientCallInterceptor interceptor(A2AAgentSource source, String userToken) {
        return switch (source) {
            case AGENT_HOST -> new A2AAuthInterceptor(tokenExchangeService, mcpFilterService, userToken);
            case LITELLM -> liteLlmAuthInterceptor;
        };
    }

    /**
     * The sticky key outlives the registry, so a chat can still be attached to an agent that was not
     * discovered after a restart. It is released, so the next message reaches the default route, and the
     * turn fails with a message saying so even when the release itself fails.
     */
    private Flux<String> releaseUnavailableAgent(UUID chatId, String agentName) {
        log.warn("A2A agent '{}' is not registered; releasing it from chat {}", agentName, chatId);

        Mono<Void> releaseAgent = a2aStickyAgentService.deactivate(chatId)
                .onErrorResume(exception -> releaseFailed(chatId, exception));

        Mono<Void> releaseTask = a2aStickyAgentService.deactivateTask(chatId)
                .onErrorResume(exception -> releaseFailed(chatId, exception));

        return Mono.when(releaseAgent, releaseTask)
                .thenMany(Flux.error(new A2AAgentUnavailableException(agentName)));
    }

    private static Mono<Void> releaseFailed(UUID chatId, Throwable exception) {
        log.warn("Could not release the A2A agent from chat {} ({})", chatId, exception.getClass().getSimpleName());
        return Mono.empty();
    }

    void handleEvent(DelegatedTurn turn, ClientEvent clientEvent) {
        turn.touch();

        switch (clientEvent) {
            case TaskUpdateEvent taskUpdateEvent -> handleUpdate(turn, taskUpdateEvent.getUpdateEvent());
            case TaskEvent taskEvent -> {
                Task task = taskEvent.getTask();
                TaskState state = task.status().state();

                if (state == TaskState.TASK_STATE_INPUT_REQUIRED) {
                    log.debug("A2A agent event handler waiting for input on task '{}' for chat {}", task.id(), turn.chatId);

                    a2aStickyAgentService.activateTask(turn.chatId, task.id())
                            .subscribe();

                    emitArtifactsIfAny(turn, task.artifacts());

                    Optional<A2APrompt> prompt = prompt(task.status().message());

                    if (prompt.isPresent()) {
                        turn.awaitAnswer(task.id(), prompt.get());
                        return;
                    }

                    turn.finish();
                } else if (state.isFinal()) {
                    a2aStickyAgentService.deactivateTask(turn.chatId).subscribe();
                    emitArtifactsIfAny(turn, task.artifacts());
                    complete(turn, state);
                }
            }
            case MessageEvent messageEvent -> {
                emitParts(turn, messageEvent.getMessage().parts());
                turn.finish();
            }
            default -> log.debug("Ignoring unknown A2A event: {}", clientEvent.getClass().getSimpleName());
        }
    }

    private void handleUpdate(DelegatedTurn turn, UpdateEvent updateEvent) {
        switch (updateEvent) {
            case TaskArtifactUpdateEvent artifactEvent -> emitArtifactUpdate(turn, artifactEvent);
            case TaskStatusUpdateEvent statusEvent -> {
                TaskState state = statusEvent.status().state();
                Message statusMessage = statusEvent.status().message();

                log.debug("Event state: {}", state);

                if (state == TaskState.TASK_STATE_INPUT_REQUIRED) {
                    log.debug("A2A agent waiting for input on task '{}' for chat {}", statusEvent.taskId(), turn.chatId);

                    a2aStickyAgentService.activateTask(turn.chatId, statusEvent.taskId())
                            .subscribe();

                    Optional<A2APrompt> prompt = prompt(statusMessage);

                    if (prompt.isPresent()) {
                        turn.awaitAnswer(statusEvent.taskId(), prompt.get());
                        return;
                    }

                    if (statusMessage != null) {
                        emitParts(turn, statusMessage.parts());
                    }

                    turn.finish();
                } else {
                    if (statusMessage != null) {
                        emitStatusNotification(turn, statusMessage);
                    }

                    if (statusEvent.isFinal() || state.isFinal()) {
                        a2aStickyAgentService.deactivateTask(turn.chatId).subscribe();
                        complete(turn, state);
                    }
                }
            }
            default -> log.debug("Ignoring unknown A2A update event: {}", updateEvent.getClass().getSimpleName());
        }
    }

    private Optional<A2APrompt> prompt(@Nullable Message statusMessage) {
        return Optional.ofNullable(statusMessage)
                .flatMap(message -> A2AElicitationExtension.prompt(message, jsonMapper));
    }

    private void emitStatusNotification(DelegatedTurn turn, Message a2aMessage) {
        String text = extractText(a2aMessage.parts());

        if (text.isEmpty()) {
            return;
        }

        notificationService.emitProgress(turn.chatId, a2aMessage);
        turn.progress();
    }

    private void emitArtifactUpdate(DelegatedTurn turn, TaskArtifactUpdateEvent artifactEvent) {
        Artifact artifact = artifactEvent.artifact();
        Optional<A2ASound> sound = A2ASoundExtension.sound(artifact, maxSoundBytes);

        if (sound.isPresent()) {
            turn.sound(sound.get());
            return;
        }

        turn.observe(artifact);

        boolean append = Boolean.TRUE.equals(artifactEvent.append());
        turn.recordArtifact(artifact.artifactId(), extractText(artifact.parts()), append);
        emitParts(turn, artifact.parts());
    }

    /**
     * A task snapshot carries every artifact the task holds. When an answered prompt continued the task,
     * that includes the artifacts this turn already showed before the prompt, so only the ones it has
     * not shown are emitted.
     */
    private void emitArtifactsIfAny(DelegatedTurn turn, List<Artifact> artifacts) {
        if (artifacts == null) {
            return;
        }

        for (Artifact artifact : artifacts) {
            if (turn.soundCued(artifact.artifactId())) {
                continue;
            }

            Optional<A2ASound> sound = A2ASoundExtension.sound(artifact, maxSoundBytes);

            if (sound.isPresent()) {
                turn.sound(sound.get());
                continue;
            }

            turn.observe(artifact);

            String text = extractText(artifact.parts());

            if (turn.alreadyShown(artifact.artifactId(), text)) {
                continue;
            }

            turn.recordArtifact(artifact.artifactId(), text, false);
            emitParts(turn, artifact.parts());
        }
    }

    private void emitParts(DelegatedTurn turn, List<Part<?>> parts) {
        if (parts == null) {
            return;
        }

        parts.stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).text())
                .filter(StringUtils::isNotEmpty)
                .forEach(turn::emit);
    }

    private String extractText(List<Part<?>> parts) {
        if (parts == null) {
            return "";
        }

        return parts.stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).text())
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.joining("\n"));
    }

    private void complete(DelegatedTurn turn, TaskState state) {
        switch (state) {
            case TASK_STATE_FAILED -> turn.fail(new IllegalStateException("A2A agent task failed"));
            case TASK_STATE_REJECTED -> turn.fail(new IllegalStateException("A2A agent task rejected"));
            default -> turn.finish();
        }
    }

    private void saveReply(UUID chatId, String reply) {
        if (StringUtils.isBlank(reply)) {
            return;
        }

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setChatId(chatId);
        chatMessage.setMessageType(MessageType.ASSISTANT);
        chatMessage.setMessage(reply.trim());

        chatMessageService.save(chatMessage);
    }

    /**
     * One delegated turn, which stays open across any prompts the agent raises. The chat memory
     * advisor that writes the assistant row on every other route only runs inside a {@code ChatClient}
     * call, which a delegated turn never makes, so the reply is saved here: the text before a prompt
     * when the prompt is raised, so history reads in the order it happened, and the rest when the turn
     * finishes. A failed, timed-out or cancelled turn saves nothing more, as on the other routes.
     */
    final class DelegatedTurn {
        private final UUID chatId;
        private final FluxSink<String> sink;
        private final StringBuilder segment = new StringBuilder();
        private final Disposable.Composite disposables = Disposables.composite();
        private final AtomicBoolean ended = new AtomicBoolean();
        private final AtomicBoolean awaitingAnswer = new AtomicBoolean();
        private final Map<String, String> shownArtifacts = new ConcurrentHashMap<>();
        private final Set<String> cuedSounds = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean textEmitted = new AtomicBoolean();
        private final ExchangeWatchdog watchdog;
        private final @Nullable SharedMode sharedMode;
        private volatile Consumer<Message> sender = _ -> {
        };

        private DelegatedTurn(UUID chatId, FluxSink<String> sink, @Nullable SharedMode sharedMode) {
            this.chatId = chatId;
            this.sink = sink;
            this.sharedMode = sharedMode;
            this.watchdog = new ExchangeWatchdog(timeout, this::fail);
        }

        /**
         * Reports the turn a shared session's reply artifact says. Nothing for a conversation, whose
         * agent was never told who is taking part.
         */
        void observe(Artifact artifact) {
            if (sharedMode == null) {
                return;
            }

            A2AParticipantsExtension.turn(artifact, sharedMode.participants()).ifPresent(sharedMode.listener()::turn);
        }

        void start(Consumer<Message> sender, Message message) {
            this.sender = sender;
            send(message);
        }

        void touch() {
            watchdog.touch();
        }

        synchronized void emit(String text) {
            if (ended.get()) {
                return;
            }

            textEmitted.set(true);
            segment.append(text);
            sink.next(text);
        }

        void progress() {
            sink.next("");
        }

        /**
         * Records the text this turn has shown for an artifact; an appended chunk adds to what was
         * shown before it.
         */
        void recordArtifact(String artifactId, String text, boolean append) {
            if (append) {
                shownArtifacts.merge(artifactId, text, (shown, chunk) -> shown + "\n" + chunk);
                return;
            }

            shownArtifacts.put(artifactId, text);
        }

        /**
         * Whether this turn has already shown this artifact with this text. An artifact replaced under
         * the same id with new text has not been shown.
         */
        boolean alreadyShown(String artifactId, String text) {
            return text.equals(shownArtifacts.get(artifactId));
        }

        boolean soundCued(String artifactId) {
            return cuedSounds.contains(artifactId);
        }

        /**
         * Cues a sound for the reply's start frame. Only the turn's first sound is played, and only
         * before any reply text: the start frame carries one cue and is published with the first
         * chunk, so a later sound could never be heard, and storing it would only cost Redis. A task
         * snapshot repeating a sound already seen is skipped. The cue is pending before this returns,
         * while the clip is stored in the background; a failed store leaves a fetch that 404s, never a
         * failed turn.
         */
        void sound(A2ASound sound) {
            if (!cuedSounds.add(sound.cueId())) {
                return;
            }

            if (ended.get() || textEmitted.get() || cuedSounds.size() > 1) {
                log.debug("Ignoring an A2A sound for chat {}: only one, sent before the reply, is played", chatId);
                return;
            }

            SoundCue cue = soundService.cue(sound);
            pendingMessageMetadata.put(chatId, A2ASoundExtension.METADATA_KEY, cue);

            soundService.store(cue.soundId(), sound)
                    .doOnError(exception -> log.warn("Could not store the A2A sound cued for chat {} ({})", chatId,
                            exception.getClass().getSimpleName()))
                    .onErrorComplete()
                    .subscribe();
        }

        /**
         * Parks the turn on the user's answer. Accepting sends the narrowed answer on the same task, and
         * the agent's reply streams into this same turn; anything else ends the turn and leaves the task
         * sticky, so the user's next typed message continues it. An agent may report the same waiting
         * state twice, as a task snapshot and as a status update, so a question already waiting is
         * asked once.
         */
        void awaitAnswer(String taskId, A2APrompt prompt) {
            if (ended.get() || !awaitingAnswer.compareAndSet(false, true)) {
                return;
            }

            //A shared session hands the question on and ends the run: its answer is the next message,
            //sent by whichever participant it is for, whenever they get to it. The task stays sticky.
            if (sharedMode != null) {
                sharedMode.listener().prompt(taskId, prompt);
                finish();
                return;
            }

            watchdog.pause();
            saveSegment();

            ElicitationService.ElicitationHandle handle;

            try {
                handle = elicitationService.prepareElicitation(chatId);
                elicitationService.emitElicitation(chatId, handle.elicitationId(), ElicitationEvent.form(chatId,
                        handle.elicitationId(), prompt.message(), prompt.requestedSchema(), prompt.meta()));
            } catch (RuntimeException exception) {
                log.warn("Could not raise the A2A prompt on task '{}' for chat {} ({})", taskId, chatId,
                        exception.getClass().getSimpleName());
                finish();
                return;
            }

            log.debug("A2A prompt {} raised on task '{}' for chat {}", handle.elicitationId(), taskId, chatId);

            Disposable answer = elicitationService.awaitResultAsync(chatId, handle.elicitationId())
                    .subscribe(result -> answered(taskId, result), this::fail);

            disposables.add(answer);
        }

        private void answered(String taskId, McpSchema.ElicitResult result) {
            awaitingAnswer.set(false);

            if (result.action() == McpSchema.ElicitResult.Action.ACCEPT && result.content() != null) {
                log.debug("Sending the accepted A2A prompt answer on task '{}' for chat {}", taskId, chatId);
                send(A2AElicitationExtension.answer(chatId, taskId, result.content()));
                return;
            }

            finish();
        }

        private void send(Message message) {
            watchdog.arm();

            try {
                sender.accept(message);
            } catch (RuntimeException exception) {
                fail(exception);
            }
        }

        void finish() {
            if (!ended.compareAndSet(false, true)) {
                return;
            }

            watchdog.stop();
            saveSegment();
            sink.complete();
        }

        void fail(Throwable error) {
            if (!ended.compareAndSet(false, true)) {
                return;
            }

            watchdog.stop();
            sink.error(error);
        }

        void dispose() {
            ended.set(true);
            disposables.dispose();
            watchdog.stop();
        }

        private void saveSegment() {
            String reply;

            synchronized (this) {
                reply = segment.toString();
                segment.setLength(0);
            }

            saveReply(chatId, reply);
        }
    }
}
