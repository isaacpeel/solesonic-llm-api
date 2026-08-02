package com.solesonic.model.security;

/**
 * The closed set of events written to the fail2ban-facing security log.
 * <p>
 * Every {@link #key()} is part of a machine interface: the jail filters under
 * {@code deploy/fail2ban} match these exact strings. Renaming one does not break a build — it
 * silently stops a jail from matching, which looks exactly like "no attacks today". Treat the
 * keys as an API and change them only alongside the filters.
 */
public enum SecurityEvent {
    /**
     * Missing, malformed, or expired credential. Banned by route classification: a 401 for a path
     * the application does not serve is a scanner, a 401 for a path it does serve could be the
     * operator's own client with a stale token.
     */
    AUTHENTICATION_FAILURE("authn.failure"),

    /**
     * Valid signature, subject outside the allowlist. Emitted once the subject allowlist exists;
     * a real token pointed at this deployment by someone else is a one-strike ban.
     */
    REJECTED_SUBJECT("authn.rejected_subject"),

    /**
     * Valid signature, foreign issuer. Emitted once the issuer validator exists.
     */
    REJECTED_ISSUER("authn.rejected_issuer"),

    /**
     * Valid signature, foreign audience. Emitted once the audience validator exists.
     */
    REJECTED_AUDIENCE("authn.rejected_audience"),

    /**
     * Authenticated, but not permitted — the 403 path.
     */
    AUTHORIZATION_DENIED("authz.denied"),

    /**
     * Scope or audience failure at the Atlassian token broker.
     */
    BROKER_DENIED("broker.denied"),

    /**
     * OAuth callback whose state does not match the one that started the flow. Emitted once the
     * callback consumes state.
     */
    OAUTH_STATE_MISMATCH("oauth.state_mismatch"),

    /**
     * A 404 for an authenticated request — a path this application does not serve.
     */
    UNKNOWN_ROUTE("route.unknown"),

    /**
     * A method this route does not support, or a body that will not parse.
     */
    METHOD_REJECTED("method.rejected"),

    /**
     * Over the per-IP request budget. Emitted once a rate limiter exists.
     */
    RATE_LIMIT_EXCEEDED("ratelimit.exceeded");

    private final String key;

    SecurityEvent(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
