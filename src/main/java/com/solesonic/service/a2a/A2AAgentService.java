package com.solesonic.service.a2a;

import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.config.a2a.A2AAuthInterceptor;
import com.solesonic.config.a2a.A2AClientProperties;
import com.solesonic.mcp.client.TokenExchangeService;
import com.solesonic.model.security.McpFilterService;
import com.solesonic.service.chat.events.NotificationService;
import org.a2aproject.sdk.client.*;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.a2aproject.sdk.spec.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Service
public class A2AAgentService {
    private static final Logger log = LoggerFactory.getLogger(A2AAgentService.class);

    private final A2AAgentRegistry agentRegistry;
    private final TokenExchangeService tokenExchangeService;
    private final McpFilterService mcpFilterService;
    private final NotificationService notificationService;
    private final A2AStickyAgentService a2aStickyAgentService;
    private final long timeoutSeconds;

    public A2AAgentService(A2AAgentRegistry agentRegistry,
                           TokenExchangeService tokenExchangeService,
                           McpFilterService mcpFilterService,
                           NotificationService notificationService,
                           A2AStickyAgentService a2aStickyAgentService,
                           A2AClientProperties properties) {
        this.agentRegistry = agentRegistry;
        this.tokenExchangeService = tokenExchangeService;
        this.mcpFilterService = mcpFilterService;
        this.notificationService = notificationService;
        this.a2aStickyAgentService = a2aStickyAgentService;
        this.timeoutSeconds = properties.timeoutSeconds();
    }

    public Flux<String> delegate(UUID chatId, String agentName, String message, String userToken) {
        return a2aStickyAgentService.getActiveTaskId(chatId)
                .flatMapMany(activeTaskId -> Flux.<String>create(sink -> {
                    AgentCard agentCard = agentRegistry.card(agentName);

                    BiConsumer<ClientEvent, AgentCard> consumer = (event, _) -> handleEvent(chatId, event, sink);

                    A2AAuthInterceptor authInterceptor = new A2AAuthInterceptor(tokenExchangeService, mcpFilterService, userToken);

                    JSONRPCTransportConfigBuilder jsonrpcTransportConfigBuilder = new JSONRPCTransportConfigBuilder()
                            .addInterceptor(authInterceptor);

                    Client client = Client.builder(agentCard)
                            .withTransport(JSONRPCTransport.class, jsonrpcTransportConfigBuilder)
                            .addConsumer(consumer)
                            .streamingErrorHandler(sink::error)
                            .build();

                    sink.onDispose(client::close);

                    Message.Builder messageBuilder = Message.builder()
                            .role(Message.Role.ROLE_USER)
                            .contextId(chatId.toString())
                            .messageId(UUID.randomUUID().toString())
                            .parts(new TextPart(message));

                    if (activeTaskId.isPresent()) {
                        log.debug("Continuing A2A task '{}' for chat {}", activeTaskId.get(), chatId);
                        messageBuilder.taskId(activeTaskId.get());
                    }

                    client.sendMessage(messageBuilder.build(), null);
                }))
                .timeout(Duration.ofSeconds(timeoutSeconds));
    }

    void handleEvent(UUID chatId, ClientEvent clientEvent, FluxSink<String> sink) {
        switch (clientEvent) {
            case TaskUpdateEvent taskUpdateEvent -> handleUpdate(chatId, taskUpdateEvent.getUpdateEvent(), sink);
            case TaskEvent taskEvent -> {
                Task task = taskEvent.getTask();
                TaskState state = task.status().state();

                if (state == TaskState.TASK_STATE_INPUT_REQUIRED) {
                    log.debug("A2A agent event handler waiting for input on task '{}' for chat {}", task.id(), chatId);

                    a2aStickyAgentService.activateTask(chatId, task.id())
                            .subscribe();

                    emitArtifactsIfAny(task.artifacts(), sink);
                    sink.complete();
                } else if (state.isFinal()) {
                    a2aStickyAgentService.deactivateTask(chatId).subscribe();
                    emitArtifactsIfAny(task.artifacts(), sink);
                    complete(state, sink);
                }
            }
            case MessageEvent messageEvent -> {
                emitParts(messageEvent.getMessage().parts(), sink);
                sink.complete();
            }
            default -> log.debug("Ignoring unknown A2A event: {}", clientEvent.getClass().getSimpleName());
        }
    }

    private void handleUpdate(UUID chatId, UpdateEvent updateEvent, FluxSink<String> sink) {
        switch (updateEvent) {
            case TaskArtifactUpdateEvent artifactEvent -> emitParts(artifactEvent.artifact().parts(), sink);
            case TaskStatusUpdateEvent statusEvent -> {
                TaskState state = statusEvent.status().state();

                log.debug("Event state: {}", state);

                if (state == TaskState.TASK_STATE_INPUT_REQUIRED) {
                    log.debug("A2A agent waiting for input on task '{}' for chat {}", statusEvent.taskId(), chatId);

                    a2aStickyAgentService.activateTask(chatId, statusEvent.taskId())
                            .subscribe();

                    if (statusEvent.status().message() != null) {
                        emitParts(statusEvent.status().message().parts(), sink);
                    }
                    sink.complete();
                } else {
                    if (statusEvent.status().message() != null) {
                        emitStatusNotification(chatId, statusEvent.status().message(), sink);
                    }

                    if (statusEvent.isFinal() || state.isFinal()) {
                        a2aStickyAgentService.deactivateTask(chatId).subscribe();
                        complete(state, sink);
                    }
                }
            }
            default -> log.debug("Ignoring unknown A2A update event: {}", updateEvent.getClass().getSimpleName());
        }
    }

    private void emitStatusNotification(UUID chatId, Message a2aMessage, FluxSink<String> sink) {
        String text = extractText(a2aMessage.parts());

        if (text.isEmpty()) {
            return;
        }

        notificationService.emitProgress(chatId, a2aMessage);
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

        parts.stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).text())
                .filter(StringUtils::isNotEmpty)
                .forEach(sink::next);
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

    private void complete(TaskState state, FluxSink<String> sink) {
        switch (state) {
            case TASK_STATE_FAILED -> sink.error(new IllegalStateException("A2A agent task failed"));
            case TASK_STATE_REJECTED -> sink.error(new IllegalStateException("A2A agent task rejected"));
            default -> sink.complete();
        }
    }
}
