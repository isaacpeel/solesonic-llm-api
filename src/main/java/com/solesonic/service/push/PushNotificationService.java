package com.solesonic.service.push;

import com.solesonic.config.interest.InterestSettings;
import com.solesonic.config.push.PushSettings;
import com.solesonic.exception.push.PushException;
import com.solesonic.model.push.PushDeliveryOptions;
import com.solesonic.model.push.PushDeliveryResult;
import com.solesonic.model.push.PushEvent;
import com.solesonic.model.push.PushPreferenceSummary;
import com.solesonic.model.push.PushSubscription;
import com.solesonic.model.push.PushTarget;
import com.solesonic.model.push.PushUrgency;
import com.solesonic.service.push.webpush.WebPushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Pushes an event to every browser a user has subscribed, in a fixed order: enabled, dedupe,
 * preference, presence, send. A reminder skips presence, and so do the interest events and a
 * shared-session invite, which have no chat anyone could already be reading.
 * <p>
 * It never fails or delays its caller. {@link #push} subscribes and walks away, and every failure
 * ends in a log line. Logs name the subscription id, the event type and the status, never the
 * endpoint, the keys or the payload.
 */
@Service
public class PushNotificationService {
    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    private static final String SENT_KEY_PREFIX = "push:sent:";
    private static final Duration DEDUPE_WINDOW = Duration.ofHours(1);
    private static final Duration TURN_TIME_TO_LIVE = Duration.ofHours(24);
    private static final Duration INTEREST_UPDATE_TIME_TO_LIVE = Duration.ofHours(24);
    private static final Duration SESSION_TIME_TO_LIVE = Duration.ofHours(24);
    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);
    private static final int SEND_CONCURRENCY = 4;
    private static final int TOO_MANY_REQUESTS = 429;

    /**
     * Stands in for a status when the push service could not be reached at all.
     */
    private static final int UNREACHABLE = 0;

    private final PushSettings pushSettings;
    private final InterestSettings interestSettings;
    private final PushPreferenceService pushPreferenceService;
    private final PushPresenceService pushPresenceService;
    private final PushSubscriptionService pushSubscriptionService;
    private final WebPushSender webPushSender;
    private final PushPayloadFactory pushPayloadFactory;
    private final ReactiveStringRedisTemplate redisTemplate;

    public PushNotificationService(PushSettings pushSettings,
                                   InterestSettings interestSettings,
                                   PushPreferenceService pushPreferenceService,
                                   PushPresenceService pushPresenceService,
                                   PushSubscriptionService pushSubscriptionService,
                                   WebPushSender webPushSender,
                                   PushPayloadFactory pushPayloadFactory,
                                   ReactiveStringRedisTemplate redisTemplate) {
        this.pushSettings = pushSettings;
        this.interestSettings = interestSettings;
        this.pushPreferenceService = pushPreferenceService;
        this.pushPresenceService = pushPresenceService;
        this.pushSubscriptionService = pushSubscriptionService;
        this.webPushSender = webPushSender;
        this.pushPayloadFactory = pushPayloadFactory;
        this.redisTemplate = redisTemplate;
    }

    public void push(UUID userId, PushEvent pushEvent) {
        if (userId == null || !pushSettings.enabled()) {
            return;
        }

        deliver(userId, pushEvent)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    log.warn("Push {} for {} failed: {}", eventType(pushEvent), pushEvent.subjectId(), error.getClass().getSimpleName());

                    return Mono.empty();
                })
                .subscribe();
    }

    /**
     * The whole delivery as one cold {@link Mono}, so a test can drive it to completion.
     */
    Mono<Void> deliver(UUID userId, PushEvent pushEvent) {
        return Mono.defer(() -> {
            if (!pushSettings.enabled()) {
                return Mono.empty();
            }

            byte[] payload = pushPayloadFactory.forEvent(pushEvent);
            PushDeliveryOptions options = options(pushEvent);

            return firstTime(pushEvent)
                    .filter(Boolean::booleanValue)
                    .flatMap(_ -> blocking(() -> wanted(userId, pushEvent)))
                    .filter(Boolean::booleanValue)
                    .flatMap(_ -> watched(userId, pushEvent))
                    .filter(watching -> !watching)
                    .flatMapMany(_ -> blocking(() -> pushSubscriptionService.subscriptions(userId))
                            .flatMapIterable(subscriptions -> subscriptions))
                    .flatMap(pushSubscription -> deliverTo(userId, pushSubscription, eventType(pushEvent), payload, options),
                            SEND_CONCURRENCY)
                    .then();
        });
    }

    private Mono<Boolean> firstTime(PushEvent pushEvent) {
        return Mono.defer(() -> redisTemplate.opsForValue().setIfAbsent(SENT_KEY_PREFIX + pushEvent.dedupeKey(), "1", DEDUPE_WINDOW))
                .defaultIfEmpty(true)
                .onErrorResume(error -> {
                    log.warn("Could not dedupe push {} for {}, sending anyway: {}",
                            eventType(pushEvent), pushEvent.subjectId(), error.getClass().getSimpleName());

                    return Mono.just(true);
                });
    }

    private boolean wanted(UUID userId, PushEvent pushEvent) {
        PushPreferenceSummary preferences = pushPreferenceService.get(userId);

        return switch (pushEvent) {
            case PushEvent.TurnFinished _, PushEvent.TurnFailed _ -> preferences.turnComplete();
            case PushEvent.ElicitationWaiting _ -> preferences.elicitation();
            case PushEvent.InterestOffered _ -> preferences.interestOffers();
            case PushEvent.InterestUpdated _ -> preferences.interestUpdates();
            case PushEvent.SessionInvited _ -> preferences.sessionInvites();
            case PushEvent.SessionTurn _, PushEvent.SessionStarted _ -> preferences.sessionTurns();
        };
    }

    /**
     * An offer has no chat, and an update's chat was created a moment ago, so nobody can be reading
     * either.
     */
    private Mono<Boolean> watched(UUID userId, PushEvent pushEvent) {
        return switch (pushEvent) {
            case PushEvent.TurnFinished turnFinished -> pushPresenceService.isWatching(userId, turnFinished.chatId());
            case PushEvent.TurnFailed turnFailed -> pushPresenceService.isWatching(userId, turnFailed.chatId());
            case PushEvent.ElicitationWaiting elicitationWaiting -> elicitationWaiting.reminder()
                    ? Mono.just(false)
                    : pushPresenceService.isWatching(userId, elicitationWaiting.chatId());
            case PushEvent.InterestOffered _, PushEvent.InterestUpdated _, PushEvent.SessionInvited _ -> Mono.just(false);
            case PushEvent.SessionTurn sessionTurn -> pushPresenceService.isWatching(userId, sessionTurn.chatId());
            case PushEvent.SessionStarted sessionStarted -> pushPresenceService.isWatching(userId, sessionStarted.chatId());
        };
    }

    /**
     * A turn's push is worth delivering for a day; an elicitation's only for as long as it can still
     * be answered; an offer's for as long as it can be accepted. The topic lets a newer push replace
     * one still queued for the same chat, question, offer or agent. Interest pushes are not urgent.
     */
    private PushDeliveryOptions options(PushEvent pushEvent) {
        return switch (pushEvent) {
            case PushEvent.TurnFinished turnFinished -> new PushDeliveryOptions(TURN_TIME_TO_LIVE, PushUrgency.HIGH, topic(turnFinished.chatId()));
            case PushEvent.TurnFailed turnFailed -> new PushDeliveryOptions(TURN_TIME_TO_LIVE, PushUrgency.HIGH, topic(turnFailed.chatId()));
            case PushEvent.ElicitationWaiting elicitationWaiting ->
                    new PushDeliveryOptions(pushSettings.elicitationTimeout(), PushUrgency.HIGH, topic(elicitationWaiting.elicitationId()));
            case PushEvent.InterestOffered interestOffered ->
                    new PushDeliveryOptions(interestSettings.offerTimeToLive(), PushUrgency.NORMAL, topic(interestOffered.offerId()));
            case PushEvent.InterestUpdated interestUpdated ->
                    new PushDeliveryOptions(INTEREST_UPDATE_TIME_TO_LIVE, PushUrgency.NORMAL, topic(interestUpdated.agentId()));
            case PushEvent.SessionInvited sessionInvited ->
                    new PushDeliveryOptions(SESSION_TIME_TO_LIVE, PushUrgency.NORMAL, topic(sessionInvited.inviteId()));
            case PushEvent.SessionTurn sessionTurn ->
                    new PushDeliveryOptions(SESSION_TIME_TO_LIVE, PushUrgency.HIGH, topic(sessionTurn.sessionId()));
            case PushEvent.SessionStarted sessionStarted ->
                    new PushDeliveryOptions(SESSION_TIME_TO_LIVE, PushUrgency.HIGH, topic(sessionStarted.sessionId()));
        };
    }

    private Mono<Void> deliverTo(UUID userId,
                                 PushSubscription pushSubscription,
                                 String eventType,
                                 byte[] payload,
                                 PushDeliveryOptions options) {
        UUID subscriptionId = pushSubscription.getId();

        return Mono.defer(() -> {
                    PushTarget target;

                    try {
                        target = pushSubscriptionService.target(pushSubscription);
                    } catch (PushException pushException) {
                        log.warn("Push subscription {} is no longer deliverable: {}", subscriptionId, pushException.getErrorCode());

                        return run(() -> pushSubscriptionService.prune(userId, subscriptionId));
                    }

                    return send(new Delivery(userId, subscriptionId, target, eventType, payload, options), 1, false);
                })
                .onErrorResume(error -> {
                    log.error("Push {} to subscription {} could not be sent: {}", eventType, subscriptionId, error.getClass().getSimpleName());

                    return Mono.empty();
                });
    }

    private Mono<Void> send(Delivery delivery, int attempt, boolean rateLimitRetried) {
        return blocking(() -> webPushSender.send(delivery.target(), delivery.payload(), delivery.options()))
                .onErrorResume(RestClientException.class, _ -> Mono.just(new PushDeliveryResult(UNREACHABLE, null)))
                .flatMap(pushDeliveryResult -> handle(delivery, attempt, rateLimitRetried, pushDeliveryResult));
    }

    private Mono<Void> handle(Delivery delivery, int attempt, boolean rateLimitRetried, PushDeliveryResult pushDeliveryResult) {
        int status = pushDeliveryResult.status();

        log.info("Push {} to subscription {} answered {} on attempt {}",
                delivery.eventType(), delivery.subscriptionId(), status == UNREACHABLE ? "unreachable" : status, attempt);

        if (PushSubscriptionService.isDelivered(status)) {
            return run(() -> pushSubscriptionService.markDelivered(delivery.userId(), delivery.subscriptionId()));
        }

        if (PushSubscriptionService.isGone(status)) {
            return run(() -> pushSubscriptionService.prune(delivery.userId(), delivery.subscriptionId()));
        }

        if (status == TOO_MANY_REQUESTS && !rateLimitRetried) {
            return Mono.delay(retryAfter(pushDeliveryResult))
                    .then(Mono.defer(() -> send(delivery, attempt + 1, true)));
        }

        if (isTransient(status) && attempt < pushSettings.maxAttempts()) {
            return Mono.delay(FIRST_RETRY_DELAY.multipliedBy(1L << (attempt - 1)))
                    .then(Mono.defer(() -> send(delivery, attempt + 1, rateLimitRetried)));
        }

        if (isTransient(status) || status == TOO_MANY_REQUESTS) {
            return run(() -> pushSubscriptionService.recordFailure(delivery.userId(), delivery.subscriptionId()));
        }

        //Not retried, but counted: a subscription that is refused every time deletes itself at max-failures.
        log.error("Push {} to subscription {} was refused with {}: the request itself is wrong",
                delivery.eventType(), delivery.subscriptionId(), status);

        return run(() -> pushSubscriptionService.recordFailure(delivery.userId(), delivery.subscriptionId()));
    }

    private static boolean isTransient(int status) {
        return status == UNREACHABLE || status >= 500;
    }

    private static Duration retryAfter(PushDeliveryResult pushDeliveryResult) {
        Duration retryAfter = pushDeliveryResult.retryAfter();

        if (retryAfter == null) {
            return FIRST_RETRY_DELAY;
        }

        return retryAfter.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : retryAfter;
    }

    private static String topic(UUID id) {
        return id.toString().replace("-", "");
    }

    private static String eventType(PushEvent pushEvent) {
        return pushEvent.getClass().getSimpleName();
    }

    private static <T> Mono<T> blocking(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<Void> run(Runnable runnable) {
        return Mono.<Void>fromRunnable(runnable).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * One subscription's share of a push, carried through its retries. Never compared, so the payload
     * array being compared by reference does not matter.
     */
    private record Delivery(UUID userId,
                            UUID subscriptionId,
                            PushTarget target,
                            String eventType,
                            byte[] payload,
                            PushDeliveryOptions options) {
    }
}
