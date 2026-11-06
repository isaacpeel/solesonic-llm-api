package com.solesonic.service.push;

import com.solesonic.config.JacksonConfig;
import com.solesonic.config.push.PushSettings;
import com.solesonic.exception.push.PushException;
import com.solesonic.model.push.PushDeliveryOptions;
import com.solesonic.model.push.PushDeliveryResult;
import com.solesonic.model.push.PushErrorCode;
import com.solesonic.model.push.PushSubscription;
import com.solesonic.model.push.PushSubscriptionRequest;
import com.solesonic.model.push.PushSubscriptionSummary;
import com.solesonic.model.push.PushTarget;
import com.solesonic.model.push.PushUrgency;
import com.solesonic.repository.push.PushSubscriptionRepository;
import com.solesonic.service.push.webpush.P256Keys;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.client.ResourceAccessException;

import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushSubscriptionServiceTest {
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final ZonedDateTime NOW_ZONED = NOW.atZone(ZoneOffset.UTC);
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/subscription-token";
    private static final String LABEL = "Chrome on Android";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14)";

    @Mock
    private PushSubscriptionRepository pushSubscriptionRepository;

    @Mock
    private WebPushSender webPushSender;

    private PushPayloadFactory pushPayloadFactory;
    private String p256dh;
    private String auth;

    @BeforeEach
    void setUp() {
        pushPayloadFactory = new PushPayloadFactory(new JacksonConfig().jsonMapper(), Clock.fixed(NOW, ZoneOffset.UTC), "Izzy");
        p256dh = BASE64_URL_ENCODER.encodeToString(P256Keys.uncompressed((ECPublicKey) P256Keys.generate().getPublic()));

        byte[] authSecret = new byte[16];
        new SecureRandom().nextBytes(authSecret);
        auth = BASE64_URL_ENCODER.encodeToString(authSecret);

        when(pushSubscriptionRepository.saveAndFlush(any(PushSubscription.class))).thenAnswer(invocation -> {
            PushSubscription pushSubscription = invocation.getArgument(0);

            if (pushSubscription.getId() == null) {
                pushSubscription.setId(SUBSCRIPTION_ID);
            }

            return pushSubscription;
        });
    }

    private PushSubscriptionService service(boolean enabled) {
        PushSettings pushSettings = new PushSettings(enabled, Set.of("fcm.googleapis.com"), 3, 5, Duration.ofSeconds(120),
                Duration.ofSeconds(600), Duration.ofSeconds(45), Duration.ofSeconds(15));

        return new PushSubscriptionService(pushSubscriptionRepository, new PushEndpointValidator(pushSettings), webPushSender,
                pushPayloadFactory, pushSettings, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PushSubscriptionRequest request(String endpoint, String label) {
        return new PushSubscriptionRequest(new PushSubscriptionRequest.SubscriptionJson(endpoint, null,
                new PushSubscriptionRequest.SubscriptionKeys(p256dh, auth)), label);
    }

    private PushSubscription stored(UUID userId, String endpoint) {
        PushSubscription pushSubscription = new PushSubscription();
        pushSubscription.setId(SUBSCRIPTION_ID);
        pushSubscription.setUserId(userId);
        pushSubscription.setEndpoint(endpoint);
        pushSubscription.setP256dh(p256dh);
        pushSubscription.setAuth(auth);
        pushSubscription.setLabel(LABEL);
        pushSubscription.setCreated(NOW_ZONED.minusDays(3));
        pushSubscription.setUpdated(NOW_ZONED.minusDays(3));
        pushSubscription.setFailureCount(3);

        return pushSubscription;
    }

    @Test
    void createsASubscriptionForTheCaller() {
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.empty());

        PushSubscriptionService.UpsertResult result = service(true).upsert(USER_ID, request(ENDPOINT, LABEL), USER_AGENT);

        assertThat(result.created()).isTrue();
        assertThat(result.summary().id()).isEqualTo(SUBSCRIPTION_ID);

        PushSubscription saved = savedSubscription();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getEndpoint()).isEqualTo(ENDPOINT);
        assertThat(saved.getP256dh()).isEqualTo(p256dh);
        assertThat(saved.getAuth()).isEqualTo(auth);
        assertThat(saved.getLabel()).isEqualTo(LABEL);
        assertThat(saved.getUserAgent()).isEqualTo(USER_AGENT);
        assertThat(saved.getCreated()).isEqualTo(NOW_ZONED);
        assertThat(saved.getUpdated()).isEqualTo(NOW_ZONED);
        assertThat(saved.getFailureCount()).isZero();
        assertThat(saved.getExpirationTime()).isNull();
    }

    @Test
    void truncatesTheUserAgentTo256Characters() {
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.empty());

        service(true).upsert(USER_ID, request(ENDPOINT, LABEL), "a".repeat(300));

        assertThat(savedSubscription().getUserAgent()).hasSize(256);
    }

    @Test
    void storesTheExpirationTime() {
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.empty());

        PushSubscriptionRequest expiring = new PushSubscriptionRequest(new PushSubscriptionRequest.SubscriptionJson(ENDPOINT,
                NOW.plus(Duration.ofDays(30)).toEpochMilli(), new PushSubscriptionRequest.SubscriptionKeys(p256dh, auth)), LABEL);

        service(true).upsert(USER_ID, expiring, USER_AGENT);

        assertThat(savedSubscription().getExpirationTime()).isEqualTo(NOW_ZONED.plusDays(30));
    }

    /**
     * Only the browser holding a subscription knows its endpoint and keys, so whoever presents them
     * is the user now signed in on that browser.
     */
    @Test
    void movesAnEndpointStoredUnderAnotherUserToTheCaller() {
        PushSubscription existing = stored(OTHER_USER_ID, ENDPOINT);
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(existing));

        PushSubscriptionService.UpsertResult result = service(true).upsert(USER_ID, request(ENDPOINT, "Pixel"), USER_AGENT);

        assertThat(result.created()).isFalse();

        PushSubscription saved = savedSubscription();
        assertThat(saved.getId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getLabel()).isEqualTo("Pixel");
        assertThat(saved.getCreated()).isEqualTo(NOW_ZONED.minusDays(3));
        assertThat(saved.getUpdated()).isEqualTo(NOW_ZONED);
        assertThat(saved.getFailureCount()).isZero();
    }

    @Test
    void evictsTheOldestSubscriptionsBeyondTwentyPerUser() {
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.empty());

        List<PushSubscription> newestFirst = new ArrayList<>();

        for (int index = 0; index < 22; index++) {
            PushSubscription pushSubscription = stored(USER_ID, ENDPOINT + index);
            pushSubscription.setId(UUID.randomUUID());
            newestFirst.add(pushSubscription);
        }

        when(pushSubscriptionRepository.findByUserIdOrderByCreatedDesc(USER_ID)).thenReturn(newestFirst);

        service(true).upsert(USER_ID, request(ENDPOINT, LABEL), USER_AGENT);

        verify(pushSubscriptionRepository).deleteByIdAndUserId(newestFirst.get(20).getId(), USER_ID);
        verify(pushSubscriptionRepository).deleteByIdAndUserId(newestFirst.get(21).getId(), USER_ID);
        verify(pushSubscriptionRepository, never()).deleteByIdAndUserId(newestFirst.get(19).getId(), USER_ID);
    }

    @Test
    void treatsAConcurrentInsertOfTheSameEndpointAsAnUpdate() {
        when(pushSubscriptionRepository.findByEndpoint(ENDPOINT))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(stored(OTHER_USER_ID, ENDPOINT)));
        when(pushSubscriptionRepository.saveAndFlush(any(PushSubscription.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate endpoint"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PushSubscriptionService.UpsertResult result = service(true).upsert(USER_ID, request(ENDPOINT, LABEL), USER_AGENT);

        assertThat(result.created()).isFalse();
        assertThat(result.summary().id()).isEqualTo(SUBSCRIPTION_ID);
    }

    @Test
    void refusesAFirefoxSubscription() {
        assertCode(() -> service(true).upsert(USER_ID, request("https://updates.push.services.mozilla.com/wpush/v2/token", LABEL), USER_AGENT),
                PushErrorCode.UNSUPPORTED_BROWSER);

        verify(pushSubscriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    void refusesABlankLabel() {
        assertCode(() -> service(true).upsert(USER_ID, request(ENDPOINT, "  "), USER_AGENT), PushErrorCode.INVALID_SUBSCRIPTION);
    }

    @Test
    void refusesALabelOver128Characters() {
        assertCode(() -> service(true).upsert(USER_ID, request(ENDPOINT, "a".repeat(129)), USER_AGENT), PushErrorCode.INVALID_SUBSCRIPTION);
    }

    @Test
    void refusesASubscriptionWithoutKeys() {
        PushSubscriptionRequest withoutKeys = new PushSubscriptionRequest(
                new PushSubscriptionRequest.SubscriptionJson(ENDPOINT, null, null), LABEL);

        assertCode(() -> service(true).upsert(USER_ID, withoutKeys, USER_AGENT), PushErrorCode.INVALID_SUBSCRIPTION);
    }

    @Test
    void refusesARequestWithoutASubscription() {
        assertCode(() -> service(true).upsert(USER_ID, new PushSubscriptionRequest(null, LABEL), USER_AGENT),
                PushErrorCode.INVALID_SUBSCRIPTION);
    }

    @Test
    void refusesToSubscribeWhileDisabled() {
        assertCode(() -> service(false).upsert(USER_ID, request(ENDPOINT, LABEL), USER_AGENT), PushErrorCode.PUSH_DISABLED);
    }

    @Test
    void listsTheCallersSubscriptions() {
        when(pushSubscriptionRepository.findByUserIdOrderByCreatedDesc(USER_ID)).thenReturn(List.of(stored(USER_ID, ENDPOINT)));

        List<PushSubscriptionSummary> summaries = service(true).list(USER_ID);

        assertThat(summaries).singleElement().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo(SUBSCRIPTION_ID);
            assertThat(summary.label()).isEqualTo(LABEL);
        });
    }

    @Test
    void deletesAnOwnedSubscription() {
        when(pushSubscriptionRepository.deleteByIdAndUserId(SUBSCRIPTION_ID, USER_ID)).thenReturn(1);

        service(true).delete(USER_ID, SUBSCRIPTION_ID);

        verify(pushSubscriptionRepository).deleteByIdAndUserId(SUBSCRIPTION_ID, USER_ID);
    }

    @Test
    void deletingASubscriptionTheCallerDoesNotOwnIsNotFound() {
        when(pushSubscriptionRepository.deleteByIdAndUserId(SUBSCRIPTION_ID, USER_ID)).thenReturn(0);

        assertCode(() -> service(true).delete(USER_ID, SUBSCRIPTION_ID), PushErrorCode.NOT_FOUND);
    }

    @Test
    void aTestPushThatIsAcceptedRecordsTheDelivery() {
        when(pushSubscriptionRepository.findByIdAndUserId(SUBSCRIPTION_ID, USER_ID)).thenReturn(Optional.of(stored(USER_ID, ENDPOINT)));
        when(webPushSender.send(any(), any(), any())).thenReturn(new PushDeliveryResult(201, null));

        service(true).test(USER_ID, SUBSCRIPTION_ID);

        ArgumentCaptor<PushTarget> target = ArgumentCaptor.forClass(PushTarget.class);
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<PushDeliveryOptions> options = ArgumentCaptor.forClass(PushDeliveryOptions.class);
        verify(webPushSender).send(target.capture(), payload.capture(), options.capture());

        assertThat(target.getValue().endpoint().toString()).isEqualTo(ENDPOINT);
        assertThat(payload.getValue()).isEqualTo(pushPayloadFactory.test());
        assertThat(options.getValue().urgency()).isEqualTo(PushUrgency.HIGH);
        verify(pushSubscriptionRepository).markDelivered(SUBSCRIPTION_ID, USER_ID, NOW_ZONED);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 410})
    void aTestPushToADeadSubscriptionDeletesIt(int status) {
        when(pushSubscriptionRepository.findByIdAndUserId(SUBSCRIPTION_ID, USER_ID)).thenReturn(Optional.of(stored(USER_ID, ENDPOINT)));
        when(webPushSender.send(any(), any(), any())).thenReturn(new PushDeliveryResult(status, null));

        assertCode(() -> service(true).test(USER_ID, SUBSCRIPTION_ID), PushErrorCode.SUBSCRIPTION_GONE);

        verify(pushSubscriptionRepository).deleteByIdAndUserId(SUBSCRIPTION_ID, USER_ID);
    }

    @Test
    void aTestPushTheServiceRejectsOtherwiseIsADeliveryFailure() {
        when(pushSubscriptionRepository.findByIdAndUserId(SUBSCRIPTION_ID, USER_ID)).thenReturn(Optional.of(stored(USER_ID, ENDPOINT)));
        when(webPushSender.send(any(), any(), any())).thenReturn(new PushDeliveryResult(500, null));

        assertCode(() -> service(true).test(USER_ID, SUBSCRIPTION_ID), PushErrorCode.DELIVERY_FAILED);

        verify(pushSubscriptionRepository, never()).deleteByIdAndUserId(any(), any());
    }

    @Test
    void aTestPushThatCannotReachThePushServiceIsADeliveryFailure() {
        when(pushSubscriptionRepository.findByIdAndUserId(SUBSCRIPTION_ID, USER_ID)).thenReturn(Optional.of(stored(USER_ID, ENDPOINT)));
        when(webPushSender.send(any(), any(), any())).thenThrow(new ResourceAccessException("connect timed out"));

        assertCode(() -> service(true).test(USER_ID, SUBSCRIPTION_ID), PushErrorCode.DELIVERY_FAILED);
    }

    @Test
    void aTestPushToAnotherUsersSubscriptionIsNotFound() {
        when(pushSubscriptionRepository.findByIdAndUserId(SUBSCRIPTION_ID, USER_ID)).thenReturn(Optional.empty());

        assertCode(() -> service(true).test(USER_ID, SUBSCRIPTION_ID), PushErrorCode.NOT_FOUND);

        verify(webPushSender, never()).send(any(), any(), any());
    }

    @Test
    void aTestPushWhileDisabledIsRefused() {
        assertCode(() -> service(false).test(USER_ID, SUBSCRIPTION_ID), PushErrorCode.PUSH_DISABLED);

        verify(webPushSender, never()).send(any(), any(), any());
    }

    /**
     * The allowlist is checked again before every send: a row stored under an older allowlist is
     * never posted to.
     */
    @Test
    void aTestPushToAnEndpointNoLongerAllowedDeletesItWithoutSending() {
        when(pushSubscriptionRepository.findByIdAndUserId(SUBSCRIPTION_ID, USER_ID))
                .thenReturn(Optional.of(stored(USER_ID, "https://push.example.com/send/token")));

        assertCode(() -> service(true).test(USER_ID, SUBSCRIPTION_ID), PushErrorCode.SUBSCRIPTION_GONE);

        verify(webPushSender, never()).send(any(), any(), any());
        verify(pushSubscriptionRepository).deleteByIdAndUserId(SUBSCRIPTION_ID, USER_ID);
    }

    @Test
    void aFailureAtTheLimitDeletesTheSubscription() {
        PushSubscription atLimit = stored(USER_ID, ENDPOINT);
        atLimit.setFailureCount(5);
        when(pushSubscriptionRepository.findByIdAndUserId(SUBSCRIPTION_ID, USER_ID)).thenReturn(Optional.of(atLimit));

        assertThat(service(true).recordFailure(USER_ID, SUBSCRIPTION_ID)).isTrue();

        verify(pushSubscriptionRepository).incrementFailureCount(SUBSCRIPTION_ID, USER_ID);
        verify(pushSubscriptionRepository).deleteByIdAndUserId(SUBSCRIPTION_ID, USER_ID);
    }

    @Test
    void aFailureBelowTheLimitKeepsTheSubscription() {
        PushSubscription belowLimit = stored(USER_ID, ENDPOINT);
        belowLimit.setFailureCount(4);
        when(pushSubscriptionRepository.findByIdAndUserId(SUBSCRIPTION_ID, USER_ID)).thenReturn(Optional.of(belowLimit));

        assertThat(service(true).recordFailure(USER_ID, SUBSCRIPTION_ID)).isFalse();

        verify(pushSubscriptionRepository, never()).deleteByIdAndUserId(any(), any());
    }

    @Test
    void marksADeliveryThroughTheOwnersRow() {
        service(true).markDelivered(USER_ID, SUBSCRIPTION_ID);

        verify(pushSubscriptionRepository).markDelivered(eq(SUBSCRIPTION_ID), eq(USER_ID), eq(NOW_ZONED));
    }

    private PushSubscription savedSubscription() {
        ArgumentCaptor<PushSubscription> saved = ArgumentCaptor.forClass(PushSubscription.class);
        verify(pushSubscriptionRepository).saveAndFlush(saved.capture());

        return saved.getValue();
    }

    private static void assertCode(Runnable call, PushErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(PushException.class,
                        pushException -> assertThat(pushException.getErrorCode()).isEqualTo(expected));
    }
}
