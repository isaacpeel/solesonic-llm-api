package com.solesonic.model.push;

import java.time.Duration;

/**
 * What the push service answered. {@code retryAfter} is null when it sent no readable
 * {@code Retry-After}.
 */
public record PushDeliveryResult(int status, Duration retryAfter) {
}
