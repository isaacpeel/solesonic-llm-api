package com.solesonic.service.a2a;

import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.config.a2a.A2AAuthInterceptor;
import com.solesonic.config.a2a.A2AClientProperties;
import com.solesonic.mcp.client.IdentityToolCallback;
import com.solesonic.service.chat.ElicitationService;
import io.a2a.client.Client;
import io.a2a.client.ClientEvent;
import io.a2a.client.MessageEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import io.a2a.spec.UpdateEvent;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "solesonic.a2a", name = "enabled", havingValue = "true")
public class A2AAgentService {

    private static final Logger log = LoggerFactory.getLogger(A2AAgentService.class);

    private final A2AAgentRegistry agentRegistry;
    private final A2AAuthInterceptor a2aAuthInterceptor;
    private final ElicitationService elicitationService;
    private final Optional<A2AStickyAgentService> a2aStickyAgentService;
    private final long timeoutSeconds;

    public A2AAgentService(A2AAgentRegistry agentRegistry,
                           A2AAuthInterceptor a2aAuthInterceptor,
                           ElicitationService elicitationService,
                           Optional<A2AStickyAgentService> a2aStickyAgentService,
                           A2AClientProperties properties) {
        this.agentRegistry = agentRegistry;
        this.a2aAuthInterceptor = a2aAuthInterceptor;
        this.elicitationService = elicitationService;
        this.a2aStickyAgentService = a2aStickyAgentService;
        this.timeoutSeconds = properties.timeoutSeconds();
    }

    public Flux<String> delegate(UUID chatId, String agentName, String message, String userToken) {
        return Flux.<String>create(sink -> {
            Client client;

            try {
                AgentCard agentCard = agentRegistry.getCard(agentName);

                BiConsumer<ClientEvent, AgentCard> consumer = (event, _) -> handleEvent(chatId, event, sink);

                JSONRPCTransportConfigBuilder jsonrpcTransportConfigBuilder = new JSONRPCTransportConfigBuilder()
                        .addInterceptor(a2aAuthInterceptor);

                client = Client.builder(agentCard)
                        .withTransport(JSONRPCTransport.class, jsonrpcTransportConfigBuilder)
                        .addConsumer(consumer)
                        .streamingErrorHandler(sink::error)
                        .build();

                sink.onDispose(client::close);

                Optional<String> activeTaskId = a2aStickyAgentService
                        .flatMap(stickyService -> stickyService.getActiveTaskId(chatId)
                                .blockOptional()
                                .orElse(Optional.empty()));

                Message.Builder messageBuilder = new Message.Builder()
                        .role(Message.Role.USER)
                        .contextId(chatId.toString())
                        .messageId(UUID.randomUUID().toString())
                        .parts(new TextPart(message));

                if (activeTaskId.isPresent()) {
                    log.debug("Continuing A2A task '{}' for chat {}", activeTaskId.get(), chatId);
                    messageBuilder.taskId(activeTaskId.get());
                }

                IdentityToolCallback.setUserTokenContext(userToken);

                try {
                    client.sendMessage(messageBuilder.build(), null);
                } finally {
                    IdentityToolCallback.clearContext();
                }
            } catch (Exception exception) {
                log.error("Failed to send message to A2A agent '{}': {}", agentName, exception.getMessage(), exception);
                sink.error(exception);
            }
        }).timeout(Duration.ofSeconds(timeoutSeconds));
    }

    void handleEvent(UUID chatId, ClientEvent clientEvent, FluxSink<String> sink) {
        switch (clientEvent) {
            case TaskUpdateEvent taskUpdateEvent -> handleUpdate(chatId, taskUpdateEvent.getUpdateEvent(), sink);
            case TaskEvent taskEvent -> {
                Task task = taskEvent.getTask();
                TaskState state = task.getStatus() != null ? task.getStatus().state() : null;

                if (state == TaskState.INPUT_REQUIRED) {
                    log.debug("A2A agent event handler waiting for input on task '{}' for chat {}", task.getId(), chatId);

                    a2aStickyAgentService.ifPresent(stickyService ->
                            stickyService
                                    .activateTask(chatId, task.getId())
                                    .subscribe());

                    emitArtifactsIfAny(task.getArtifacts(), sink);
                    sink.complete();
                } else if (state != null && state.isFinal()) {
                    a2aStickyAgentService.ifPresent(stickyService ->
                            stickyService.deactivateTask(chatId).subscribe());
                    emitArtifactsIfAny(task.getArtifacts(), sink);
                    complete(state, sink);
                }
            }
            case MessageEvent messageEvent -> {
                emitParts(messageEvent.getMessage().getParts(), sink);
                sink.complete();
            }
            default -> log.debug("Ignoring unknown A2A event: {}", clientEvent.getClass().getSimpleName());
        }
    }

    private void handleUpdate(UUID chatId, UpdateEvent updateEvent, FluxSink<String> sink) {
        switch (updateEvent) {
            case TaskArtifactUpdateEvent artifactEvent -> emitParts(artifactEvent.getArtifact().parts(), sink);
            case TaskStatusUpdateEvent statusEvent -> {
                TaskState state = statusEvent.getStatus().state();

                if (state == TaskState.INPUT_REQUIRED) {
                    log.debug("A2A agent waiting for input on task '{}' for chat {}", statusEvent.getTaskId(), chatId);

                    a2aStickyAgentService.ifPresent(stickyService ->
                            stickyService
                                    .activateTask(chatId, statusEvent.getTaskId())
                                    .subscribe());
                    if (statusEvent.getStatus().message() != null) {
                        emitParts(statusEvent.getStatus().message().getParts(), sink);
                    }
                    sink.complete();
                } else {
                    if (statusEvent.getStatus().message() != null) {
                        emitStatusNotification(chatId, statusEvent.getStatus().message(), sink);
                    }

                    if (statusEvent.isFinal()) {
                        a2aStickyAgentService.ifPresent(stickyService ->
                                stickyService.deactivateTask(chatId).subscribe());
                        complete(state, sink);
                    }
                }
            }
            default -> log.debug("Ignoring unknown A2A update event: {}", updateEvent.getClass().getSimpleName());
        }
    }

    private void emitStatusNotification(UUID chatId, Message statusMessage, FluxSink<String> sink) {
        String text = extractText(statusMessage.getParts());

        if (text.isEmpty()) {
            return;
        }

        McpSchema.ProgressNotification progressNotification = new McpSchema.ProgressNotification(
                chatId.toString(), null, null, text, null);

        elicitationService.emitProgress(chatId, progressNotification);
        sink.next("");
    }

    private void emitArtifactsIfAny(List<Artifact> artifacts, FluxSink<String> sink) {
        if (artifacts == null) {
            return;
        }

        for (Artifact artifact : artifacts) {
            emitParts(artifact.parts(), sink);
        }
    }

    private void emitParts(List<Part<?>> parts, FluxSink<String> sink) {
        if (parts == null) {
            return;
        }

        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart) {
                String text = textPart.getText();

                if (text != null && !text.isEmpty()) {
                    sink.next(text);
                }
            }
        }
    }

    private String extractText(List<Part<?>> parts) {
        if (parts == null) {
            return "";
        }

        return parts.stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).getText())
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.joining("\n"));
    }

    private void complete(TaskState state, reactor.core.publisher.FluxSink<String> sink) {
        switch (state) {
            case FAILED -> sink.error(new IllegalStateException("A2A agent task failed"));
            case REJECTED -> sink.error(new IllegalStateException("A2A agent task rejected"));
            default -> sink.complete();
        }
    }
}
