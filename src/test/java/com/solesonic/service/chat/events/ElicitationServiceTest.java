package com.solesonic.service.chat.events;

import com.agui.community.core.message.ToolMessage;
import com.solesonic.mcp.client.elicitation.ElicitationProvider;
import com.solesonic.model.chat.ElicitationEvent;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.push.ElicitationReminderScheduler;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Mock
    private ElicitationReminderScheduler elicitationReminderScheduler;

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

        elicitationService = new ElicitationService(jsonMapper, chatMessageService, redisTemplate,
                new SideChannelPublisher(jsonMapper, redisTemplate), elicitationReminderScheduler);
        ReflectionTestUtils.setField(elicitationService, "timeoutSeconds", 60L);
    }

    /**
     * A stop button has no pending elicitation to answer, so the signal {@code cancelEvents} listens
     * for must be reachable on its own — not only as a side effect of declining a form.
     */
    @Test
    void cancelChatPublishesTheCancelSignalOnTheChatsEventsChannel() {
        UUID chatId = UUID.randomUUID();
        AtomicReference<SideChannelMessage> received = new AtomicReference<>();
        CountDownLatch countDownLatch = new CountDownLatch(1);

        elicitationService.registerChat(chatId)
                .take(1)
                .subscribe(message -> {
                    received.set(message);
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

        SideChannelMessage message = received.get();
        assertThat(message.event()).isEqualTo(ElicitationService.CANCEL_ACTION);
        assertThat(message.data().asString()).isEqualTo(ElicitationService.CANCEL_ACTION);
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
    void emitStoresThePropertiesTheElicitationAskedFor() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        elicitationService.emitElicitation(chatId, elicitationId, formRequest(chatId,
                Map.of("type", "object", "properties", Map.of("assigneeAccountId", Map.of("type", "string")))));

        ArgumentCaptor<String> storedSchema = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(schemaKey(chatId, elicitationId)), storedSchema.capture(), any(Duration.class));

        Map<String, Object> storedProperties = jsonMapper.readValue(storedSchema.getValue(), new TypeReference<>() {
        });
        assertThat(storedProperties).containsOnlyKeys("assigneeAccountId");
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

        when(valueOperations.getAndDelete(schemaKey(chatId, elicitationId)))
                .thenReturn(Mono.just("{\"assigneeAccountId\":{\"type\":\"string\"}}"));
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

    /**
     * The bug this pins: after a page refresh, chat history only has the raw submitted value (an
     * opaque account id) to show, because nothing resolved it against the schema's labels before
     * persisting. {@code summary} is what lets history show "Isaac" instead of the id, or "Accept"
     * as a last resort.
     */
    @Test
    void acceptedAnswerRecordsAHumanReadableSummaryResolvedAgainstTheSchemasOneOfLabels() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        String schemaJson = "{\"assigneeAccountId\":{\"type\":\"string\",\"title\":\"Assignee\",\"oneOf\":["
                + "{\"const\":\"70121:ad77bd3b-88c0-4373-ab9d-db11b7b9dae9\",\"title\":\"Isaac\"},"
                + "{\"const\":\"712020:dcd2f546\",\"title\":\"Evan\"}]}}";

        when(valueOperations.getAndDelete(schemaKey(chatId, elicitationId))).thenReturn(Mono.just(schemaJson));
        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(elicitationService.completeFromFrontend(actionResult(chatId, elicitationId,
                        McpSchema.ElicitResult.Action.ACCEPT,
                        Map.of("assigneeAccountId", "70121:ad77bd3b-88c0-4373-ab9d-db11b7b9dae9", "chatId", "chat-1"))))
                .expectNext(true)
                .verifyComplete();

        verify(chatMessageService).updateElicitationResponse(eq(chatId), eq(elicitationId),
                argThat(response -> "Isaac".equals(response.get("summary"))));
    }

    /**
     * A value that matches no listed option (free text, or a stale const) still has to show
     * something in history rather than silently dropping the field.
     */
    @Test
    void acceptedAnswerSummaryFallsBackToTheRawValueWhenNoLabelMatches() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        when(valueOperations.getAndDelete(schemaKey(chatId, elicitationId)))
                .thenReturn(Mono.just("{\"note\":{\"type\":\"string\",\"title\":\"Note\"}}"));
        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(elicitationService.completeFromFrontend(actionResult(chatId, elicitationId,
                        McpSchema.ElicitResult.Action.ACCEPT, Map.of("note", "Ship it Friday"))))
                .expectNext(true)
                .verifyComplete();

        verify(chatMessageService).updateElicitationResponse(eq(chatId), eq(elicitationId),
                argThat(response -> "Ship it Friday".equals(response.get("summary"))));
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

    /**
     * An A2A agent's prompt arrives as its parts, not as an MCP request, and must take the same path:
     * the question in history, the schema stored for narrowing, the publish and the reminder.
     */
    @Test
    void anElicitationEventIsSavedStoredPublishedAndReminded() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        elicitationService.emitElicitation(chatId, elicitationId, ElicitationEvent.form(chatId, elicitationId,
                "Play or pass?", Map.of("type", "object", "properties", Map.of("choice", Map.of("type", "string"))),
                Map.of("feud/prompt", "play_or_pass")));

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageService).save(saved.capture());
        assertThat(saved.getValue().getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(saved.getValue().getElicitationId()).isEqualTo(elicitationId);
        assertThat(saved.getValue().getMessage()).isEqualTo("Play or pass?");

        verify(valueOperations).set(eq(schemaKey(chatId, elicitationId)), anyString(), any(Duration.class));

        ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq("elicitation:events:" + chatId), envelope.capture());
        JsonNode data = jsonMapper.readTree(envelope.getValue()).path("data");
        assertThat(data.path("elicitationId").asString()).isEqualTo(elicitationId.toString());
        assertThat(data.path("_meta").path("feud/prompt").asString()).isEqualTo("play_or_pass");

        verify(elicitationReminderScheduler).waiting(chatId, elicitationId);
    }

    @Test
    void emittingAnElicitationPushesItAndSchedulesTheReminder() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        elicitationService.emitElicitation(chatId, elicitationId, formRequest(chatId, Map.of("type", "object")));

        verify(elicitationReminderScheduler).waiting(chatId, elicitationId);
    }

    /**
     * The translator takes the tool-call id from {@code elicitationId}, and the MCP server's own
     * metadata travels as {@code _meta} — the names the client reads.
     */
    @Test
    void theEmittedElicitationCarriesBothIdsAndMetaUnderItsWireName() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        elicitationService.emitElicitation(chatId, elicitationId, formRequest(chatId, Map.of("type", "object")));

        ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq("elicitation:events:" + chatId), envelope.capture());

        JsonNode published = jsonMapper.readTree(envelope.getValue());
        assertThat(published.path("event").asString()).isEqualTo(ElicitationService.ELICITATION);

        JsonNode data = published.path("data");
        assertThat(data.path("elicitationId").asString()).isEqualTo(elicitationId.toString());
        assertThat(data.path("chatId").asString()).isEqualTo(chatId.toString());
        assertThat(data.path("message").asString()).isEqualTo("Who should this be assigned to?");
        assertThat(data.path("_meta").path("chatId").asString()).isEqualTo(chatId.toString());
    }

    /**
     * Fails the parked tool call now rather than at its timeout, and leaves no question in history
     * that was never asked.
     */
    @Test
    void anElicitationThatCannotBeSerializedSavesNothingAndSchedulesNoReminder() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();
        McpSchema.ElicitFormRequest request = new McpSchema.ElicitFormRequest("Who?", Map.of("type", "object"),
                Map.of("chatId", chatId.toString(), "unwritable", new Unserializable()));

        assertThatThrownBy(() -> elicitationService.emitElicitation(chatId, elicitationId, request))
                .isInstanceOf(JacksonException.class);

        verify(chatMessageService, never()).save(any());
        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
        verify(elicitationReminderScheduler, never()).waiting(any(), any());
    }

    public static final class Unserializable {
        public String getValue() {
            throw new IllegalStateException("unwritable");
        }
    }

    @Test
    void anAnsweredElicitationCancelsItsReminder() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        when(valueOperations.getAndDelete(fieldsKey(chatId, elicitationId))).thenReturn(Mono.empty());

        StepVerifier.create(elicitationService.awaitResultAsync(chatId, elicitationId))
                .then(() -> messageRelay.tryEmitNext("ACCEPT"))
                .expectNextCount(1)
                .verifyComplete();

        verify(elicitationReminderScheduler, timeout(1000)).cancel(elicitationId);
    }

    @Test
    void aTimedOutElicitationCancelsItsReminder() {
        ReflectionTestUtils.setField(elicitationService, "timeoutSeconds", 1L);
        UUID elicitationId = UUID.randomUUID();

        StepVerifier.create(elicitationService.awaitResultAsync(UUID.randomUUID(), elicitationId))
                .expectNextCount(1)
                .verifyComplete();

        verify(elicitationReminderScheduler, timeout(1000)).cancel(elicitationId);
    }

    @Test
    void closingTheChatCancelsTheReminderOfAnOpenElicitation() {
        UUID chatId = UUID.randomUUID();
        UUID elicitationId = UUID.randomUUID();

        when(setOperations.members("elicitation:pending:" + chatId)).thenReturn(Flux.just(elicitationId.toString()));
        when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
        lenient().when(valueOperations.getAndDelete(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(elicitationService.awaitResultAsync(chatId, elicitationId))
                .then(() -> elicitationService.closeChat(chatId))
                .assertNext(elicitResult -> assertThat(elicitResult.action()).isEqualTo(McpSchema.ElicitResult.Action.DECLINE))
                .verifyComplete();

        verify(elicitationReminderScheduler, timeout(1000)).cancel(elicitationId);
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
