package com.solesonic.service.chat.events;

import com.agui.community.core.message.ToolMessage;
import com.solesonic.mcp.client.elicitation.ElicitationProvider;
import com.solesonic.service.chat.ChatMessageService;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ElicitationServiceTest {

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    @Mock
    private ReactiveSetOperations<String, String> setOperations;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private ElicitationService elicitationService;
    private Sinks.Many<String> messageRelay;

    @BeforeEach
    void setUp() {
        messageRelay = Sinks.many().multicast().onBackpressureBuffer();

        lenient().doReturn(valueOperations).when(redisTemplate).opsForValue();
        lenient().doReturn(setOperations).when(redisTemplate).opsForSet();
        lenient().when(setOperations.remove(anyString(), any())).thenReturn(Mono.just(1L));

        Flux<ReactiveSubscription.Message<String, String>> listenerFlux = messageRelay.asFlux()
                .map(body -> new FixedChannelMessage("channel", body));

        lenient().doReturn(listenerFlux).when(redisTemplate).listenToChannel(any(String.class));

        lenient().when(redisTemplate.convertAndSend(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    messageRelay.tryEmitNext(invocation.getArgument(1));
                    return Mono.just(1L);
                });

        elicitationService = new ElicitationService(jsonMapper, chatMessageService, redisTemplate);
        ReflectionTestUtils.setField(elicitationService, "timeoutSeconds", 60L);
    }

    /**
     * A stop button has no pending elicitation to answer, so the signal {@code cancelEvents} listens
     * for must be reachable on its own — not only as a side effect of declining a form.
     */
    @Test
    void cancelChatPublishesTheCancelSignalOnTheChatsEventsChannel() {
        UUID chatId = UUID.randomUUID();
        AtomicReference<ServerSentEvent<?>> received = new AtomicReference<>();
        CountDownLatch countDownLatch = new CountDownLatch(1);

        elicitationService.registerChat(chatId)
                .take(1)
                .subscribe(serverSentEvent -> {
                    received.set(serverSentEvent);
                    countDownLatch.countDown();
                });

        StepVerifier.create(elicitationService.cancelChat(chatId)).verifyComplete();

        boolean eventReceived;
        try {
            eventReceived = countDownLatch.await(Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interruptedException);
        }

        assertThat(eventReceived).isTrue();

        ServerSentEvent<?> serverSentEvent = received.get();
        assertThat(serverSentEvent.event()).isEqualTo(ElicitationService.CANCEL_ACTION);
        assertThat(serverSentEvent.data()).isEqualTo(ElicitationService.CANCEL_ACTION);
    }

    /**
     * Pub/sub delivers only to a subscriber already attached — a signal sent before that
     * subscription is live reaches no one. The receiver count reflects that, but the call itself
     * must still complete rather than error.
     */
    @Test
    void cancelChatCompletesEvenWhenNoOneIsListening() {
        UUID chatId = UUID.randomUUID();

        when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(0L));

        StepVerifier.create(elicitationService.cancelChat(chatId)).verifyComplete();
    }

    @Test
    void actionResultReadsTheActionOutOfTheToolMessageContent() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        Optional<ElicitationProvider.ElicitationActionResult> actionResult = elicitationService.actionResult(
                chatId, elicitationId, toolMessage(elicitationId, "{\"action\":\"accept\"}"));

        assertThat(actionResult).contains(new ElicitationProvider.ElicitationActionResult(
                McpSchema.ElicitResult.Action.ACCEPT, chatId, elicitationId, Map.of()));
    }

    /**
     * The client sends its form values flat, alongside {@code action}. Every one of them is kept
     * here; narrowing them to what the elicitation asked for happens once the stored schema is read.
     */
    @Test
    void actionResultKeepsTheFormValuesSentAlongsideTheAction() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        Optional<ElicitationProvider.ElicitationActionResult> actionResult = elicitationService.actionResult(
                chatId, elicitationId,
                toolMessage(elicitationId, "{\"assigneeAccountId\":\"account-1\",\"chatId\":\"chat-1\",\"action\":\"accept\"}"));

        assertThat(actionResult).map(ElicitationProvider.ElicitationActionResult::content)
                .contains(Map.of("assigneeAccountId", "account-1", "chatId", "chat-1"));
    }

    @Test
    void emitStoresThePropertyNamesTheElicitationAskedFor() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        elicitationService.emitElicitation(chatId, elicitationId, formRequest(chatId,
                Map.of("type", "object", "properties", Map.of("assigneeAccountId", Map.of("type", "string")))));

        ArgumentCaptor<String> storedSchema = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(schemaKey(chatId, elicitationId)), storedSchema.capture(), any(Duration.class));

        assertThat(jsonMapper.readValue(storedSchema.getValue(), String[].class)).containsExactly("assigneeAccountId");
    }

    @Test
    void emitStoresNoSchemaWhenTheElicitationAsksForNothing() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        elicitationService.emitElicitation(chatId, elicitationId, formRequest(chatId, Map.of("type", "object")));

        verify(valueOperations, never()).set(eq(schemaKey(chatId, elicitationId)), anyString(), any(Duration.class));
    }

    /**
     * The UI pre-fills {@code chatId} into its form values. Only what the elicitation's schema named
     * may reach the MCP tool.
     */
    @Test
    void acceptedAnswerIsNarrowedToTheRequestedSchema() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        when(valueOperations.getAndDelete(schemaKey(chatId, elicitationId))).thenReturn(Mono.just("[\"assigneeAccountId\"]"));
        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(elicitationService.completeFromFrontend(actionResult(chatId, elicitationId,
                        McpSchema.ElicitResult.Action.ACCEPT, Map.of("assigneeAccountId", "account-1", "chatId", "chat-1"))))
                .expectNext(true)
                .verifyComplete();

        assertThat(storedAnswerContent(chatId, elicitationId)).isEqualTo(Map.of("assigneeAccountId", "account-1"));
        verify(chatMessageService).updateElicitationResponse(eq(chatId), eq(elicitationId),
                argThat(response -> "ACCEPT".equals(response.get("action"))
                        && Map.of("assigneeAccountId", "account-1").equals(response.get("content"))));
    }

    @Test
    void declinedAnswerCarriesNoContent() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(elicitationService.completeFromFrontend(actionResult(chatId, elicitationId,
                        McpSchema.ElicitResult.Action.DECLINE, Map.of("assigneeAccountId", "account-1"))))
                .expectNext(true)
                .verifyComplete();

        assertThat(storedAnswerContent(chatId, elicitationId)).isNull();
    }

    @Test
    void cancelledAnswerCarriesNoContent() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(elicitationService.completeFromFrontend(actionResult(chatId, elicitationId,
                        McpSchema.ElicitResult.Action.CANCEL, Map.of("assigneeAccountId", "account-1"))))
                .expectNext(true)
                .verifyComplete();

        assertThat(storedAnswerContent(chatId, elicitationId)).isNull();
    }

    /**
     * An expired schema entry means nothing says which values were asked for. Forwarding the raw
     * payload would hand the tool whatever the client sent, so it forwards nothing.
     */
    @Test
    void acceptedAnswerCarriesNoContentOnceTheSchemaHasExpired() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        when(valueOperations.getAndDelete(schemaKey(chatId, elicitationId))).thenReturn(Mono.empty());
        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(elicitationService.completeFromFrontend(actionResult(chatId, elicitationId,
                        McpSchema.ElicitResult.Action.ACCEPT, Map.of("assigneeAccountId", "account-1"))))
                .expectNext(true)
                .verifyComplete();

        assertThat(storedAnswerContent(chatId, elicitationId)).isNull();
    }

    /**
     * An unreadable schema entry is treated as an expired one. Erroring instead would never signal
     * the parked tool call, leaving it to wait out the whole timeout.
     */
    @Test
    void acceptedAnswerStillCompletesWhenTheStoredSchemaIsUnreadable() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        when(valueOperations.getAndDelete(schemaKey(chatId, elicitationId))).thenReturn(Mono.just("not json"));
        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(elicitationService.completeFromFrontend(actionResult(chatId, elicitationId,
                        McpSchema.ElicitResult.Action.ACCEPT, Map.of("assigneeAccountId", "account-1"))))
                .expectNext(true)
                .verifyComplete();

        assertThat(storedAnswerContent(chatId, elicitationId)).isNull();
    }

    @Test
    void awaitedResultCarriesTheStoredContentAndNoMeta() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        when(valueOperations.getAndDelete(fieldsKey(chatId, elicitationId)))
                .thenReturn(Mono.just("{\"action\":\"ACCEPT\",\"content\":{\"assigneeAccountId\":\"account-1\"}}"));

        StepVerifier.create(elicitationService.awaitResultAsync(chatId, elicitationId))
                .then(() -> messageRelay.tryEmitNext("ACCEPT"))
                .assertNext(elicitResult -> {
                    assertThat(elicitResult.action()).isEqualTo(McpSchema.ElicitResult.Action.ACCEPT);
                    assertThat(elicitResult.content()).isEqualTo(Map.of("assigneeAccountId", "account-1"));
                    assertThat(elicitResult.meta()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void awaitedResultDeclinesWithNoContentOnTimeout() {
        ReflectionTestUtils.setField(elicitationService, "timeoutSeconds", 1L);

        StepVerifier.create(elicitationService.awaitResultAsync(UUID.randomUUID(), UUID.randomUUID()))
                .assertNext(elicitResult -> {
                    assertThat(elicitResult.action()).isEqualTo(McpSchema.ElicitResult.Action.DECLINE);
                    assertThat(elicitResult.content()).isNull();
                })
                .verifyComplete();
    }

    private Map<String, Object> storedAnswerContent(UUID chatId, UUID elicitationId) {
        ArgumentCaptor<String> storedAnswer = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(fieldsKey(chatId, elicitationId)), storedAnswer.capture(), any(Duration.class));

        Map<String, Object> answer = jsonMapper.readValue(storedAnswer.getValue(), new TypeReference<>() {
        });

        return jsonMapper.convertValue(answer.get("content"), new TypeReference<>() {
        });
    }

    private static ElicitationProvider.ElicitationActionResult actionResult(UUID chatId,
                                                                            UUID elicitationId,
                                                                            McpSchema.ElicitResult.Action action,
                                                                            Map<String, Object> content) {
        return new ElicitationProvider.ElicitationActionResult(action, chatId, elicitationId, content);
    }

    private static McpSchema.ElicitFormRequest formRequest(UUID chatId, Map<String, Object> requestedSchema) {
        return new McpSchema.ElicitFormRequest("Who should this be assigned to?", requestedSchema,
                Map.of("chatId", chatId.toString()));
    }

    private static String schemaKey(UUID chatId, UUID elicitationId) {
        return "elicitation:schema:" + chatId + ":" + elicitationId;
    }

    private static String fieldsKey(UUID chatId, UUID elicitationId) {
        return "elicitation:fields:" + chatId + ":" + elicitationId;
    }

    @Test
    void actionResultIsEmptyWhenTheContentNamesNoAction() {
        UUID elicitationId = UUID.randomUUID();

        assertThat(elicitationService.actionResult(UUID.randomUUID(), elicitationId,
                toolMessage(elicitationId, "{\"answer\":\"yes\"}"))).isEmpty();
    }

    @Test
    void actionResultIsEmptyForAnUnknownAction() {
        UUID elicitationId = UUID.randomUUID();

        assertThat(elicitationService.actionResult(UUID.randomUUID(), elicitationId,
                toolMessage(elicitationId, "{\"action\":\"maybe\"}"))).isEmpty();
    }

    @Test
    void actionResultIsEmptyWhenTheContentIsNotJson() {
        UUID elicitationId = UUID.randomUUID();

        assertThat(elicitationService.actionResult(UUID.randomUUID(), elicitationId,
                toolMessage(elicitationId, "accept"))).isEmpty();
        assertThat(elicitationService.actionResult(UUID.randomUUID(), elicitationId,
                toolMessage(elicitationId, " "))).isEmpty();
    }

    private static ToolMessage toolMessage(UUID elicitationId, String content) {
        return new ToolMessage("message-1", content, elicitationId.toString());
    }

    private record FixedChannelMessage(String channelName, String messageBody)
            implements ReactiveSubscription.Message<String, String> {

        @Override
        public @NonNull String getChannel() {
            return channelName;
        }

        @Override
        public @NonNull String getMessage() {
            return messageBody;
        }
    }
}
