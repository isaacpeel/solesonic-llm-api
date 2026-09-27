package com.solesonic.exception.google;

/**
 * The client-facing shape of every Google failure: a stable code to branch on and a message safe to
 * show a user. Google's own wording never reaches here — it names internal identifiers and is
 * written for developers.
 */
public record GoogleErrorResponse(String code, String message) {

    /** The grant is gone or was never given. Retrying cannot fix it; the user must consent again. */
    public static final String RECONNECT_REQUIRED = "RECONNECT_REQUIRED";

    /** Google is throttling. Back off and retry. */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    /** Google, or the call to it, failed in a way that may succeed on a retry. */
    public static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";

    /** Anything else. */
    public static final String INTERNAL = "INTERNAL";
}
