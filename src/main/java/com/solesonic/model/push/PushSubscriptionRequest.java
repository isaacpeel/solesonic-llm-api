package com.solesonic.model.push;

import org.jspecify.annotations.NonNull;

/**
 * {@code subscription} is exactly what the browser's {@code PushSubscription.toJSON()} returns. No
 * part of it is ever rendered by {@code toString}.
 */
public record PushSubscriptionRequest(SubscriptionJson subscription, String label) {

    public record SubscriptionJson(String endpoint, Long expirationTime, SubscriptionKeys keys) {

        @Override
        public @NonNull String toString() {
            return "SubscriptionJson[redacted]";
        }
    }

    public record SubscriptionKeys(String p256dh, String auth) {

        @Override
        public @NonNull String toString() {
            return "SubscriptionKeys[redacted]";
        }
    }
}
