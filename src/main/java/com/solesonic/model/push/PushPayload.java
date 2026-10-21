package com.solesonic.model.push;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What the service worker receives after decryption. {@code url} is always a same-origin path.
 * {@code version} changes only on a breaking change; {@code actions} is optional and left out when
 * there are none, so every payload without one is byte-identical to before it existed.
 */
public record PushPayload(int version,
                          PushPayloadType type,
                          String title,
                          String body,
                          String url,
                          String tag,
                          String timestamp,
                          @JsonInclude(JsonInclude.Include.NON_EMPTY) List<PushAction> actions) {
}
