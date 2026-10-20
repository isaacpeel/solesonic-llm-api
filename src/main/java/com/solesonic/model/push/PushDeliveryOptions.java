package com.solesonic.model.push;

import java.time.Duration;

/**
 * The RFC 8030 delivery headers for one push. {@code topic} may be null; when present it is at most
 * 32 base64url characters, and a newer push with the same topic replaces one still queued.
 */
public record PushDeliveryOptions(Duration timeToLive, PushUrgency urgency, String topic) {
}
