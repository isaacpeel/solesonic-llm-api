package com.solesonic.service.a2a;

import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.config.a2a.A2AAuthInterceptor;
import com.solesonic.config.a2a.A2AClientProperties;
import com.solesonic.config.a2a.LiteLlmA2AAuthInterceptor;
import com.solesonic.exception.a2a.A2AAgentUnavailableException;
import com.solesonic.mcp.client.TokenExchangeService;
import com.solesonic.model.a2a.A2AAgentSource;
import com.solesonic.model.a2a.A2AElicitationExtension;
import com.solesonic.model.a2a.A2APrompt;
import com.solesonic.model.a2a.RegisteredAgent;
import com.solesonic.model.chat.ElicitationEvent;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.security.McpFilterService;
import com.solesonic.service.chat.ChatMessageService;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
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

    public A2AAgentService(A2AAgentRegistry agentRegistry,
                           TokenExchangeService tokenExchangeService,
                           McpFilterService mcpFilterService,
                           NotificationService notificationService,
                           A2AStickyAgentService a2aStickyAgentService,
                           ChatMessageService chatMessageService,
                           ElicitationService elicitationService,
                           JsonMapper jsonMapper,
                           A2AClientProperties properties) {
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
    }

    /**
     * @param userToken used only for an agent-host agent; a LiteLLM agent is called with the virtual key
     *                  alone, so the user's token never reaches the gateway.
     */
    public Flux<String> delegate(UUID chatId, String agentName, String message, String userToken) {
        return Flux.defer(() -> agentRegistry.agent(agentName)
                .map(registeredAgent -> delegate(chatId, registeredAgent, message, userToken))
                .orElseGet(() -> releaseUnavailableAgent(chatId, agentName)));
    }

    private Flux<String> delegate(UUID chatId, RegisteredAgent registeredAgent, String message, String userToken) {
        return a2aStickyAgentService.getActiveTaskId(chatId)
                .flatMapMany(activeTaskId -> Flux.create(sink -> {
                    DelegatedTurn turn = turn(chatId, sink);
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
                            firstMessage(chatId, message, activeTaskId.orElse(null)));
                }));
    }

    Message firstMessage(UUID chatId, String message, @Nullable String activeTaskId) {
        return Message.builder()
                .role(Message.Role.ROLE_USER)
                .contextId(chatId.toString())
                .taskId(activeTaskId)
                .messageId(UUID.randomUUID().toString())
                .extensions(List.of(A2AElicitationExtension.URI))
                .parts(new TextPart(message))
                .build();
    }

    DelegatedTurn turn(UUID chatId, FluxSink<String> sink) {
        return new DelegatedTurn(chatId, sink);
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
            case TaskArtifactUpdateEvent artifactEvent -> emitParts(turn, artifactEvent.artifact().parts());
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

    private void emitArtifactsIfAny(DelegatedTurn turn, List<Artifact> artifacts) {
        if (artifacts == null) {
            return;
        }

        for (Artifact artifact : artifacts) {
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
        private final ExchangeWatchdog watchdog;
        private volatile Consumer<Message> sender = _ -> {
        };

        private DelegatedTurn(UUID chatId, FluxSink<String> sink) {
            this.chatId = chatId;
            this.sink = sink;
            this.watchdog = new ExchangeWatchdog(timeout, this::fail);
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

            segment.append(text);
            sink.next(text);
        }

        void progress() {
            sink.next("");
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
