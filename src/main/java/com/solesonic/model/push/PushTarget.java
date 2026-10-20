package com.solesonic.model.push;

import org.jspecify.annotations.NonNull;

import java.net.URI;

/**
 * Where one push goes, and the browser's keys it is encrypted to. The endpoint is a bearer capability
 * for that browser, so this never renders its components.
 */
public record PushTarget(URI endpoint, String p256dh, String auth) {

    @Override
    public @NonNull String toString() {
        return "PushTarget[redacted]";
    }
}
