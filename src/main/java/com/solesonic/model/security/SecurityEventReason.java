package com.solesonic.model.security;

/**
 * Why a {@link SecurityEvent} fired. A closed enum rather than free text, for the same reason the
 * events are: fail2ban filters key off these strings, and prose drifts until the regexes quietly
 * stop matching.
 */
public enum SecurityEventReason {
    /**
     * The grammar has fixed arity — an absent value is written, never omitted.
     */
    NONE("-"),

    MISSING_TOKEN("missing_token"),
    MALFORMED_TOKEN("malformed_token"),
    EXPIRED_TOKEN("expired_token"),
    INVALID_SIGNATURE("invalid_signature"),
    WRONG_SUBJECT("wrong_subject"),
    WRONG_ISSUER("wrong_issuer"),
    WRONG_AUDIENCE("wrong_audience"),
    MISSING_SCOPE("missing_scope"),
    INSUFFICIENT_AUTHORITY("insufficient_authority"),
    UNKNOWN_ROUTE("unknown_route"),
    MALFORMED_BODY("malformed_body"),
    METHOD_NOT_ALLOWED("method_not_allowed"),
    STATE_MISMATCH("state_mismatch"),
    OVER_LIMIT("over_limit");

    private final String key;

    SecurityEventReason(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
