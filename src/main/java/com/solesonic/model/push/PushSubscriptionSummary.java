package com.solesonic.model.push;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * The only shape a subscription takes on the wire: never its endpoint or keys. Nulls are written
 * out, so {@code lastDelivered} reads as {@code null} rather than missing before a first delivery.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PushSubscriptionSummary(UUID id,
                                      String label,
                                      String userAgent,
                                      ZonedDateTime created,
                                      ZonedDateTime updated,
                                      ZonedDateTime lastDelivered) {

    public static PushSubscriptionSummary of(PushSubscription pushSubscription) {
        return new PushSubscriptionSummary(pushSubscription.getId(),
                pushSubscription.getLabel(),
                pushSubscription.getUserAgent(),
                pushSubscription.getCreated(),
                pushSubscription.getUpdated(),
                pushSubscription.getLastDelivered());
    }
}
