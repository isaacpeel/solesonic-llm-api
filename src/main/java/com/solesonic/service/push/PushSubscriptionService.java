package com.solesonic.service.push;

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
import com.solesonic.service.push.webpush.WebPushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A user's browser subscriptions. Every read and write is scoped to the owner, except the upsert's
 * endpoint lookup: an endpoint already stored under another user moves to the caller, since only
 * the browser holding a subscription knows its endpoint and keys.
 * <p>
 * Logs name a subscription by id and never by endpoint.
 */
@Service
public class PushSubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(PushSubscriptionService.class);

    public static final int MAX_LABEL_LENGTH = 128;
    public static final int MAX_USER_AGENT_LENGTH = 256;
    public static final int MAX_SUBSCRIPTIONS_PER_USER = 20;

    private static final Duration TEST_TIME_TO_LIVE = Duration.ofMinutes(5);
    private static final Set<Integer> GONE_STATUSES = Set.of(403, 404, 410);

    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final PushEndpointValidator pushEndpointValidator;
    private final WebPushSender webPushSender;
    private final PushPayloadFactory pushPayloadFactory;
    private final PushSettings pushSettings;
    private final Clock clock;

    public PushSubscriptionService(PushSubscriptionRepository pushSubscriptionRepository,
                                   PushEndpointValidator pushEndpointValidator,
                                   WebPushSender webPushSender,
                                   PushPayloadFactory pushPayloadFactory,
                                   PushSettings pushSettings,
                                   Clock clock) {
        this.pushSubscriptionRepository = pushSubscriptionRepository;
        this.pushEndpointValidator = pushEndpointValidator;
        this.webPushSender = webPushSender;
        this.pushPayloadFactory = pushPayloadFactory;
        this.pushSettings = pushSettings;
        this.clock = clock;
    }

    public record UpsertResult(PushSubscriptionSummary summary, boolean created) {
    }

    public UpsertResult upsert(UUID userId, PushSubscriptionRequest pushSubscriptionRequest, String userAgent) {
        requireEnabled();

        if (pushSubscriptionRequest == null
                || pushSubscriptionRequest.subscription() == null
                || pushSubscriptionRequest.subscription().keys() == null) {
            throw new PushException(PushErrorCode.INVALID_SUBSCRIPTION, "A subscription with its keys is required.");
        }

        String label = pushSubscriptionRequest.label() == null ? "" : pushSubscriptionRequest.label().strip();

        if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
            throw new PushException(PushErrorCode.INVALID_SUBSCRIPTION, "A label of 1 to " + MAX_LABEL_LENGTH + " characters is required.");
        }

        PushSubscriptionRequest.SubscriptionJson subscription = pushSubscriptionRequest.subscription();
        pushEndpointValidator.validate(subscription.endpoint(), subscription.keys().p256dh(), subscription.keys().auth());

        try {
            return store(userId, subscription, label, userAgent);
        } catch (DataIntegrityViolationException dataIntegrityViolationException) {
            //Another request stored the same endpoint between this one's read and write, so it is now an update.
            return store(userId, subscription, label, userAgent);
        }
    }

    private UpsertResult store(UUID userId, PushSubscriptionRequest.SubscriptionJson subscription, String label, String userAgent) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        Optional<PushSubscription> existing = pushSubscriptionRepository.findByEndpoint(subscription.endpoint());

        PushSubscription pushSubscription = existing.orElseGet(() -> {
            PushSubscription created = new PushSubscription();
            created.setEndpoint(subscription.endpoint());
            created.setCreated(now);

            return created;
        });

        if (existing.isPresent() && !userId.equals(pushSubscription.getUserId())) {
            log.info("Push subscription {} moved to user {}", pushSubscription.getId(), userId);
        }

        pushSubscription.setUserId(userId);
        pushSubscription.setP256dh(subscription.keys().p256dh());
        pushSubscription.setAuth(subscription.keys().auth());
        pushSubscription.setExpirationTime(expirationTime(subscription.expirationTime()));
        pushSubscription.setLabel(label);
        pushSubscription.setUserAgent(truncate(userAgent));
        pushSubscription.setUpdated(now);
        pushSubscription.setFailureCount(0);

        PushSubscription saved = pushSubscriptionRepository.saveAndFlush(pushSubscription);
        evictBeyondLimit(userId);

        return new UpsertResult(PushSubscriptionSummary.of(saved), existing.isEmpty());
    }

    /**
     * Bounds how far one user's push fans out. The oldest go first: a browser still in use simply
     * subscribes again.
     */
    private void evictBeyondLimit(UUID userId) {
        List<PushSubscription> newestFirst = pushSubscriptionRepository.findByUserIdOrderByCreatedDesc(userId);

        if (newestFirst.size() <= MAX_SUBSCRIPTIONS_PER_USER) {
            return;
        }

        for (PushSubscription evicted : newestFirst.subList(MAX_SUBSCRIPTIONS_PER_USER, newestFirst.size())) {
            log.info("Push subscription {} evicted: user {} is over {} subscriptions", evicted.getId(), userId, MAX_SUBSCRIPTIONS_PER_USER);
            prune(userId, evicted.getId());
        }
    }

    public List<PushSubscriptionSummary> list(UUID userId) {
        return subscriptions(userId).stream()
                .map(PushSubscriptionSummary::of)
                .toList();
    }

    public List<PushSubscription> subscriptions(UUID userId) {
        return pushSubscriptionRepository.findByUserIdOrderByCreatedDesc(userId);
    }

    public void delete(UUID userId, UUID subscriptionId) {
        if (pushSubscriptionRepository.deleteByIdAndUserId(subscriptionId, userId) == 0) {
            throw PushException.notFound();
        }
    }

    /**
     * One synchronous send, bypassing preferences and presence, so a user can see the whole path
     * works. Nothing is retried: the answer is for a person waiting on it.
     */
    public void test(UUID userId, UUID subscriptionId) {
        requireEnabled();

        PushSubscription pushSubscription = pushSubscriptionRepository.findByIdAndUserId(subscriptionId, userId)
                .orElseThrow(PushException::notFound);

        PushTarget target;

        try {
            target = target(pushSubscription);
        } catch (PushException pushException) {
            log.warn("Push subscription {} is no longer deliverable: {}", subscriptionId, pushException.getErrorCode());
            prune(userId, subscriptionId);

            throw gone();
        }

        PushDeliveryResult pushDeliveryResult;

        try {
            pushDeliveryResult = webPushSender.send(target, pushPayloadFactory.test(),
                    new PushDeliveryOptions(TEST_TIME_TO_LIVE, PushUrgency.HIGH, null));
        } catch (RuntimeException runtimeException) {
            log.warn("Test push to subscription {} failed: {}", subscriptionId, runtimeException.getClass().getSimpleName());

            throw deliveryFailed();
        }

        log.info("Test push to subscription {} answered {}", subscriptionId, pushDeliveryResult.status());

        if (isDelivered(pushDeliveryResult.status())) {
            markDelivered(userId, subscriptionId);

            return;
        }

        if (isGone(pushDeliveryResult.status())) {
            prune(userId, subscriptionId);

            throw gone();
        }

        throw deliveryFailed();
    }

    /**
     * Re-validates what was stored, so a row written under an older allowlist is never posted to.
     */
    public PushTarget target(PushSubscription pushSubscription) {
        return pushEndpointValidator.validate(pushSubscription.getEndpoint(), pushSubscription.getP256dh(), pushSubscription.getAuth());
    }

    public void markDelivered(UUID userId, UUID subscriptionId) {
        pushSubscriptionRepository.markDelivered(subscriptionId, userId, ZonedDateTime.now(clock));
    }

    public void prune(UUID userId, UUID subscriptionId) {
        pushSubscriptionRepository.deleteByIdAndUserId(subscriptionId, userId);
    }

    /**
     * @return whether the subscription reached {@code max-failures} and was deleted
     */
    public boolean recordFailure(UUID userId, UUID subscriptionId) {
        pushSubscriptionRepository.incrementFailureCount(subscriptionId, userId);

        boolean exhausted = pushSubscriptionRepository.findByIdAndUserId(subscriptionId, userId)
                .map(pushSubscription -> pushSubscription.getFailureCount() >= pushSettings.maxFailures())
                .orElse(false);

        if (exhausted) {
            log.info("Push subscription {} deleted after {} failed deliveries", subscriptionId, pushSettings.maxFailures());
            prune(userId, subscriptionId);
        }

        return exhausted;
    }

    public static boolean isDelivered(int status) {
        return status >= 200 && status < 300;
    }

    public static boolean isGone(int status) {
        return GONE_STATUSES.contains(status);
    }

    private void requireEnabled() {
        if (!pushSettings.enabled()) {
            throw PushException.disabled();
        }
    }

    private static ZonedDateTime expirationTime(Long epochMilliseconds) {
        if (epochMilliseconds == null) {
            return null;
        }

        return Instant.ofEpochMilli(epochMilliseconds).atZone(ZoneOffset.UTC);
    }

    private static String truncate(String userAgent) {
        if (userAgent == null || userAgent.length() <= MAX_USER_AGENT_LENGTH) {
            return userAgent;
        }

        return userAgent.substring(0, MAX_USER_AGENT_LENGTH);
    }

    private static PushException gone() {
        return new PushException(PushErrorCode.SUBSCRIPTION_GONE, "This browser is no longer subscribed.");
    }

    private static PushException deliveryFailed() {
        return new PushException(PushErrorCode.DELIVERY_FAILED, "The push service did not accept the notification.");
    }
}
