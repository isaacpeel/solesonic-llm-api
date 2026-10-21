package com.solesonic.service.push;

import com.solesonic.config.JacksonConfig;
import com.solesonic.config.push.PushSettings;
import com.solesonic.exception.push.PushException;
import com.solesonic.exception.push.WebPushException;
import com.solesonic.model.push.PushDeliveryOptions;
import com.solesonic.model.push.PushDeliveryResult;
import com.solesonic.model.push.PushErrorCode;
import com.solesonic.model.push.PushEvent;
import com.solesonic.model.push.PushPreferenceSummary;
import com.solesonic.model.push.PushSubscription;
import com.solesonic.model.push.PushTarget;
import com.solesonic.model.push.PushUrgency;
import com.solesonic.service.interest.InterestSettingsFixture;
import com.solesonic.service.push.webpush.WebPushSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The fixed order — enabled, dedupe, preference, presence, send — and what each push-service answer
 * does to the subscription.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushNotificationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final UUID RUN_ID = UUID.fromString("1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed");
    private static final UUID ELICITATION_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final UUID OTHER_SUBSCRIPTION_ID = UUID.randomUUID();
    private static final PushEvent TURN_FINISHED = new PushEvent.TurnFinished(CHAT_ID, RUN_ID);
    private static final PushEvent ELICITATION_WAITING = new PushEvent.ElicitationWaiting(CHAT_ID, ELICITATION_ID, false);
    private static final PushEvent ELICITATION_REMINDER = new PushEvent.ElicitationWaiting(CHAT_ID, ELICITATION_ID, true);
    private static final UUID OFFER_ID = UUID.fromString("2c1f3a6e-9d0b-4c7a-8e5f-1b2d3c4a5e6f");
    private static final UUID AGENT_ID = UUID.fromString("9a8b7c6d-5e4f-4031-2a1b-2c3d4e5f6a7b");
    private static final UUID UPDATE_ID = UUID.fromString("3d2c1b0a-9f8e-4d7c-6b5a-4f3e2d1c0b9a");
    private static final PushEvent INTEREST_OFFERED = new PushEvent.InterestOffered(OFFER_ID);
    private static final PushEvent INTEREST_UPDATED = new PushEvent.InterestUpdated(CHAT_ID, AGENT_ID, UPDATE_ID);
    private static final PushTarget TARGET = new PushTarget(URI.create("https://fcm.googleapis.com/fcm/send/first"), "p256dh", "auth");
    private static final PushTarget OTHER_TARGET = new PushTarget(URI.create("https://fcm.googleapis.com/fcm/send/second"), "p256dh", "auth");

    @Mock
    private PushPreferenceService pushPreferenceService;

    @Mock
    private PushPresenceService pushPresenceService;

    @Mock
    private PushSubscriptionService pushSubscriptionService;

    @Mock
    private WebPushSender webPushSender;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    private final JsonMapper jsonMapper = new JacksonConfig().jsonMapper();
    private PushSubscription subscription;
    private PushSubscription otherSubscription;

    @BeforeEach
    void setUp() {
        subscription = subscription(SUBSCRIPTION_ID);
        otherSubscription = subscription(OTHER_SUBSCRIPTION_ID);

        doReturn(valueOperations).when(redisTemplate).opsForValue();
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        when(pushPreferenceService.get(USER_ID)).thenReturn(PushPreferenceSummary.DEFAULT);
        when(pushPresenceService.isWatching(USER_ID, CHAT_ID)).thenReturn(Mono.just(false));
        when(pushSubscriptionService.subscriptions(USER_ID)).thenReturn(List.of(subscription));
        when(pushSubscriptionService.target(subscription)).thenReturn(TARGET);
        when(pushSubscriptionService.target(otherSubscription)).thenReturn(OTHER_TARGET);
        when(webPushSender.send(any(), any(), any())).thenReturn(new PushDeliveryResult(201, null));
    }

    private PushNotificationService service(boolean enabled) {
        PushSettings pushSettings = new PushSettings(enabled, Set.of("fcm.googleapis.com"), 3, 5, Duration.ofSeconds(120),
                Duration.ofSeconds(600), Duration.ofSeconds(45), Duration.ofSeconds(15));

        return new PushNotificationService(pushSettings, InterestSettingsFixture.settings(), pushPreferenceService, pushPresenceService,
                pushSubscriptionService, webPushSender, new PushPayloadFactory(jsonMapper, Clock.fixed(NOW, ZoneOffset.UTC), "Izzy"),
                redisTemplate);
    }

    private static PushSubscription subscription(UUID subscriptionId) {
        PushSubscription pushSubscription = new PushSubscription();
        pushSubscription.setId(subscriptionId);
        pushSubscription.setUserId(USER_ID);

        return pushSubscription;
    }

    private void deliver(PushEvent pushEvent) {
        StepVerifier.create(service(true).deliver(USER_ID, pushEvent)).verifyComplete();
    }

    @Test
    void sendsNothingWhileDisabled() {
        StepVerifier.create(service(false).deliver(USER_ID, TURN_FINISHED)).verifyComplete();

        verifyNoInteractions(redisTemplate, pushPreferenceService, webPushSender);
    }

    @Test
    void sendsAFinishedTurnToTheUsersSubscription() {
        deliver(TURN_FINISHED);

        ArgumentCaptor<PushDeliveryOptions> options = ArgumentCaptor.forClass(PushDeliveryOptions.class);
        verify(webPushSender).send(eq(TARGET), any(), options.capture());
        assertThat(options.getValue()).isEqualTo(new PushDeliveryOptions(Duration.ofHours(24), PushUrgency.HIGH,
                "0f8fad5bd9cb469fa16570867728950e"));
        verify(pushSubscriptionService).markDelivered(USER_ID, SUBSCRIPTION_ID);
    }

    /**
     * The exact bytes handed to the sender: no message text, no chat title, no tool data.
     */
    @Test
    void thePayloadCarriesNothingFromTheConversation() {
        deliver(TURN_FINISHED);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(webPushSender).send(any(), payload.capture(), any());
        assertThat(jsonMapper.readTree(payload.getValue())).isEqualTo(jsonMapper.readTree("""
                {"version":1,"type":"TURN_FINISHED","title":"Izzy","body":"Your reply is ready",
                 "url":"/chat/0f8fad5b-d9cb-469f-a165-70867728950e","tag":"chat:0f8fad5b-d9cb-469f-a165-70867728950e",
                 "timestamp":"2026-10-20T12:00:00Z"}
                """));
    }

    @Test
    void anElicitationLivesOnlyAsLongAsItCanBeAnswered() {
        deliver(ELICITATION_WAITING);

        ArgumentCaptor<PushDeliveryOptions> options = ArgumentCaptor.forClass(PushDeliveryOptions.class);
        verify(webPushSender).send(any(), any(), options.capture());
        assertThat(options.getValue()).isEqualTo(new PushDeliveryOptions(Duration.ofSeconds(600), PushUrgency.HIGH,
                "7c9e6679742540de944be07fc1f90ae7"));
    }

    @Test
    void sendsToEverySubscription() {
        when(pushSubscriptionService.subscriptions(USER_ID)).thenReturn(List.of(subscription, otherSubscription));

        deliver(TURN_FINISHED);

        verify(webPushSender).send(eq(TARGET), any(), any());
        verify(webPushSender).send(eq(OTHER_TARGET), any(), any());
    }

    @Test
    void theSameEventIsSentOnce() {
        when(valueOperations.setIfAbsent("push:sent:turn:" + RUN_ID, "1", Duration.ofHours(1)))
                .thenReturn(Mono.just(true))
                .thenReturn(Mono.just(false));

        deliver(TURN_FINISHED);
        deliver(TURN_FINISHED);

        verify(webPushSender, times(1)).send(any(), any(), any());
    }

    @Test
    void aReminderIsDedupedApartFromTheFirstPush() {
        deliver(ELICITATION_WAITING);
        deliver(ELICITATION_REMINDER);

        verify(valueOperations).setIfAbsent("push:sent:elicitation:" + ELICITATION_ID, "1", Duration.ofHours(1));
        verify(valueOperations).setIfAbsent("push:sent:elicitation:" + ELICITATION_ID + ":reminder", "1", Duration.ofHours(1));
    }

    @Test
    void aDedupeFailureStillSends() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.error(new RedisConnectionFailureException("redis down")));

        deliver(TURN_FINISHED);

        verify(webPushSender).send(any(), any(), any());
    }

    @Test
    void aTurnIsNotSentWhenTurnNotificationsAreOff() {
        when(pushPreferenceService.get(USER_ID)).thenReturn(new PushPreferenceSummary(false, true, true, true));

        deliver(TURN_FINISHED);
        deliver(new PushEvent.TurnFailed(CHAT_ID, UUID.randomUUID()));

        verify(webPushSender, never()).send(any(), any(), any());
    }

    @Test
    void anElicitationIsNotSentWhenElicitationNotificationsAreOff() {
        when(pushPreferenceService.get(USER_ID)).thenReturn(new PushPreferenceSummary(true, false, true, true));

        deliver(ELICITATION_WAITING);
        deliver(ELICITATION_REMINDER);

        verify(webPushSender, never()).send(any(), any(), any());
    }

    @Test
    void nothingIsSentWhileTheChatIsWatched() {
        when(pushPresenceService.isWatching(USER_ID, CHAT_ID)).thenReturn(Mono.just(true));

        deliver(TURN_FINISHED);
        deliver(ELICITATION_WAITING);

        verify(webPushSender, never()).send(any(), any(), any());
    }

    /**
     * A reminder means the question has sat unanswered while someone may well be looking at it, so
     * presence does not hold it back.
     */
    @Test
    void aReminderIsSentEvenWhileTheChatIsWatched() {
        when(pushPresenceService.isWatching(USER_ID, CHAT_ID)).thenReturn(Mono.just(true));

        deliver(ELICITATION_REMINDER);

        verify(webPushSender).send(any(), any(), any());
        verify(pushPresenceService, never()).isWatching(any(), any());
    }

    @Test
    void anOfferIsSentForAsLongAsItCanBeAcceptedWithoutAskingAboutPresence() {
        deliver(INTEREST_OFFERED);

        ArgumentCaptor<PushDeliveryOptions> options = ArgumentCaptor.forClass(PushDeliveryOptions.class);
        verify(webPushSender).send(eq(TARGET), any(), options.capture());
        assertThat(options.getValue()).isEqualTo(new PushDeliveryOptions(Duration.ofDays(7), PushUrgency.NORMAL,
                "2c1f3a6e9d0b4c7a8e5f1b2d3c4a5e6f"));
        verify(pushPresenceService, never()).isWatching(any(), any());
    }

    /**
     * The chat was created a moment ago, so nobody can be reading it; the topic is the agent, so a
     * newer update replaces an older one still queued.
     */
    @Test
    void anUpdateIsSentWithoutAskingAboutPresenceAndReplacesTheAgentsQueuedUpdate() {
        when(pushPresenceService.isWatching(any(), any())).thenReturn(Mono.just(true));

        deliver(INTEREST_UPDATED);

        ArgumentCaptor<PushDeliveryOptions> options = ArgumentCaptor.forClass(PushDeliveryOptions.class);
        verify(webPushSender).send(eq(TARGET), any(), options.capture());
        assertThat(options.getValue()).isEqualTo(new PushDeliveryOptions(Duration.ofHours(24), PushUrgency.NORMAL,
                "9a8b7c6d5e4f40312a1b2c3d4e5f6a7b"));
        verify(pushPresenceService, never()).isWatching(any(), any());
    }

    @Test
    void anOfferIsNotSentWhenOfferNotificationsAreOff() {
        when(pushPreferenceService.get(USER_ID)).thenReturn(new PushPreferenceSummary(true, true, false, true));

        deliver(INTEREST_OFFERED);

        verify(webPushSender, never()).send(any(), any(), any());
    }

    @Test
    void anUpdateIsNotSentWhenUpdateNotificationsAreOffButAnOfferStillIs() {
        when(pushPreferenceService.get(USER_ID)).thenReturn(new PushPreferenceSummary(true, true, true, false));

        deliver(INTEREST_UPDATED);
        verify(webPushSender, never()).send(any(), any(), any());

        deliver(INTEREST_OFFERED);
        verify(webPushSender).send(any(), any(), any());
    }

    @Test
    void interestEventsAreDedupedByOfferAndByRun() {
        deliver(INTEREST_OFFERED);
        deliver(INTEREST_UPDATED);

        verify(valueOperations).setIfAbsent("push:sent:interest-offer:" + OFFER_ID, "1", Duration.ofHours(1));
        verify(valueOperations).setIfAbsent("push:sent:interest-update:" + UPDATE_ID, "1", Duration.ofHours(1));
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 410})
    void aDeadSubscriptionIsDeleted(int status) {
        when(webPushSender.send(any(), any(), any())).thenReturn(new PushDeliveryResult(status, null));

        deliver(TURN_FINISHED);

        verify(pushSubscriptionService).prune(USER_ID, SUBSCRIPTION_ID);
        verify(webPushSender, times(1)).send(any(), any(), any());
    }

    /**
     * Not retried and not deleted outright, but counted, so a subscription the push service refuses
     * every time cannot sit in the table being sent to forever.
     */
    @ParameterizedTest
    @ValueSource(ints = {400, 401, 413})
    void aRequestThePushServiceRejectsIsCountedButNotRetried(int status) {
        when(webPushSender.send(any(), any(), any())).thenReturn(new PushDeliveryResult(status, null));

        deliver(TURN_FINISHED);

        verify(webPushSender, times(1)).send(any(), any(), any());
        verify(pushSubscriptionService, never()).prune(any(), any());
        verify(pushSubscriptionService).recordFailure(USER_ID, SUBSCRIPTION_ID);
    }

    @Test
    void aRateLimitIsRetriedOnceAfterRetryAfter() {
        when(webPushSender.send(any(), any(), any()))
                .thenReturn(new PushDeliveryResult(429, Duration.ofSeconds(30)))
                .thenReturn(new PushDeliveryResult(201, null));

        StepVerifier.withVirtualTime(() -> service(true).deliver(USER_ID, TURN_FINISHED))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(29))
                .then(() -> verify(webPushSender, times(1)).send(any(), any(), any()))
                .thenAwait(Duration.ofSeconds(1))
                .verifyComplete();

        verify(webPushSender, times(2)).send(any(), any(), any());
        verify(pushSubscriptionService).markDelivered(USER_ID, SUBSCRIPTION_ID);
    }

    @Test
    void retryAfterIsCappedAtSixtySeconds() {
        when(webPushSender.send(any(), any(), any()))
                .thenReturn(new PushDeliveryResult(429, Duration.ofMinutes(10)))
                .thenReturn(new PushDeliveryResult(201, null));

        StepVerifier.withVirtualTime(() -> service(true).deliver(USER_ID, TURN_FINISHED))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(60))
                .verifyComplete();

        verify(webPushSender, times(2)).send(any(), any(), any());
    }

    @Test
    void aSecondRateLimitCountsAsAFailure() {
        when(webPushSender.send(any(), any(), any())).thenReturn(new PushDeliveryResult(429, Duration.ofSeconds(5)));

        StepVerifier.withVirtualTime(() -> service(true).deliver(USER_ID, TURN_FINISHED))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(5))
                .verifyComplete();

        verify(webPushSender, times(2)).send(any(), any(), any());
        verify(pushSubscriptionService).recordFailure(USER_ID, SUBSCRIPTION_ID);
    }

    @Test
    void aServerErrorIsRetriedWithBackoffThenCountedAsAFailure() {
        when(webPushSender.send(any(), any(), any())).thenReturn(new PushDeliveryResult(503, null));

        StepVerifier.withVirtualTime(() -> service(true).deliver(USER_ID, TURN_FINISHED))
                .expectSubscription()
                .then(() -> verify(webPushSender, times(1)).send(any(), any(), any()))
                .thenAwait(Duration.ofSeconds(1))
                .then(() -> verify(webPushSender, times(2)).send(any(), any(), any()))
                .thenAwait(Duration.ofMillis(1999))
                .then(() -> verify(webPushSender, times(2)).send(any(), any(), any()))
                .thenAwait(Duration.ofMillis(1))
                .verifyComplete();

        verify(webPushSender, times(3)).send(any(), any(), any());
        verify(pushSubscriptionService).recordFailure(USER_ID, SUBSCRIPTION_ID);
        verify(pushSubscriptionService, never()).prune(any(), any());
    }

    @Test
    void anUnreachablePushServiceIsRetriedThenCountedAsAFailure() {
        when(webPushSender.send(any(), any(), any())).thenThrow(new ResourceAccessException("connect timed out"));

        StepVerifier.withVirtualTime(() -> service(true).deliver(USER_ID, TURN_FINISHED))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(3))
                .verifyComplete();

        verify(webPushSender, times(3)).send(any(), any(), any());
        verify(pushSubscriptionService).recordFailure(USER_ID, SUBSCRIPTION_ID);
    }

    @Test
    void aSenderExceptionNeverPropagatesAndOtherSubscriptionsStillReceive() {
        when(pushSubscriptionService.subscriptions(USER_ID)).thenReturn(List.of(subscription, otherSubscription));
        when(webPushSender.send(eq(TARGET), any(), any())).thenThrow(new WebPushException("Could not encrypt the push message", null));

        deliver(TURN_FINISHED);

        verify(pushSubscriptionService).markDelivered(USER_ID, OTHER_SUBSCRIPTION_ID);
        verify(pushSubscriptionService, never()).markDelivered(USER_ID, SUBSCRIPTION_ID);
    }

    @Test
    void anEndpointNoLongerAllowedIsDeletedWithoutSending() {
        when(pushSubscriptionService.target(subscription))
                .thenThrow(new PushException(PushErrorCode.UNSUPPORTED_BROWSER, "Push notifications are supported in Chrome only."));

        deliver(TURN_FINISHED);

        verify(webPushSender, never()).send(any(), any(), any());
        verify(pushSubscriptionService).prune(USER_ID, SUBSCRIPTION_ID);
    }

    /**
     * The caller is a turn or an elicitation: nothing here may fail or delay it.
     */
    @Test
    void pushNeverThrowsAtItsCaller() {
        when(pushPreferenceService.get(USER_ID)).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> service(true).push(USER_ID, TURN_FINISHED)).doesNotThrowAnyException();

        verify(pushPreferenceService, timeout(1000)).get(USER_ID);
        verify(webPushSender, never()).send(any(), any(), any());
    }

    @Test
    void anAnonymousTurnIsNotPushed() {
        service(true).push(null, TURN_FINISHED);

        verifyNoInteractions(redisTemplate, pushPreferenceService, webPushSender);
    }
}
