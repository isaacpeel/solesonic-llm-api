package com.solesonic.model.push;

/**
 * What the service worker receives after decryption. {@code url} is always a same-origin path.
 * {@code version} changes only on a breaking change.
 */
public record PushPayload(int version,
                          PushPayloadType type,
                          String title,
                          String body,
                          String url,
                          String tag,
                          String timestamp) {
}
