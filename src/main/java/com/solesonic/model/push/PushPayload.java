package com.solesonic.model.push;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * What the service worker receives after decryption. {@code url} is always a same-origin path.
 * {@code version} changes only on a breaking change; {@code actions} is optional and left out when
 * there are none, so every payload without one is byte-identical to before it existed. {@code sessionId}
 * and {@code chatId} are left out unless the event names them.
 */
public record PushPayload(int version,
                          PushPayloadType type,
                          String title,
                          String body,
                          String url,
                          String tag,
                          String timestamp,
                          @JsonInclude(JsonInclude.Include.NON_EMPTY) List<PushAction> actions,
                          @JsonInclude(JsonInclude.Include.NON_NULL) UUID sessionId,
                          @JsonInclude(JsonInclude.Include.NON_NULL) UUID chatId) {

    /**
     * A payload with no ids of its own beyond the ones in {@code url}.
     */
    public PushPayload(int version, PushPayloadType type, String title, String body, String url, String tag,
                       String timestamp, List<PushAction> actions) {
        this(version, type, title, body, url, tag, timestamp, actions, null, null);
    }
}
