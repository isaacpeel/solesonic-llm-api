package com.solesonic.exception.xero;

/**
 * The client-facing shape of every Xero failure: a stable code to branch on and a message safe to
 * show a user. Xero's own wording never reaches here — an OAuth error body names client and tenant
 * identifiers and is written for developers.
 */
public record XeroErrorResponse(String code, String message) {

    /** The grant is gone or was never given. Retrying cannot fix it; the user must reconnect. */
    public static final String RECONNECT_REQUIRED = "RECONNECT_REQUIRED";

    /** Xero is throttling: 60 requests/minute per app, 5,000/day per org, 5 concurrent per org. */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    /** Xero, or the call to it, failed in a way that may succeed on a retry. */
    public static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";

    /**
     * Xero rejected the content of a request. Unlike the other codes, the accompanying message is
     * Xero's own — accounting validation text is written for the person who submitted it. Not yet
     * raised anywhere: invoice creation is what will use it.
     */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    /** Anything else. */
    public static final String INTERNAL = "INTERNAL";
}
