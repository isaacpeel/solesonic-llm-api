package com.solesonic.util;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * Builds the {@code 200} response for a server-sent event stream, with the headers that keep the
 * bytes moving.
 * <p>
 * {@code X-Accel-Buffering: no} is the load-bearing one. nginx buffers a proxied response by
 * default, which holds back exactly the frames whose value is in arriving early — the {@code init}
 * frame a client needs before it can recover anything, and the keepalive comments that stop a
 * silent connection from being reaped. Setting it on the response means the guarantee travels with
 * the endpoint instead of depending on every deployment getting its proxy config right.
 */
public final class SseResponse {
    public static final String ACCEL_BUFFERING = "X-Accel-Buffering";
    public static final String NO_BUFFERING = "no";
    public static final String NO_CACHE = "no-cache";

    private SseResponse() {
    }

    public static ResponseEntity<Flux<ServerSentEvent<?>>> ok(Flux<ServerSentEvent<?>> events) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NO_CACHE)
                .header(ACCEL_BUFFERING, NO_BUFFERING)
                .body(events);
    }
}
