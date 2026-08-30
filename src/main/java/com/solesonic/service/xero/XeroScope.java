package com.solesonic.service.xero;

import java.util.List;

/**
 * Scopes requested during Xero consent.
 * <p>
 * {@code offline_access} is mandatory, not optional hygiene: without it Xero never returns a
 * {@code refresh_token} at all, and the connection dies silently 30 minutes later when the access
 * token expires with nothing able to renew it.
 * <p>
 * Adding a scope here means re-consent — Xero grants only what the user approved, and an existing
 * refresh token does not widen retroactively.
 */
public class XeroScope {

    public static final List<String> SCOPES = List.of(
            "openid",
            "profile",
            "email",
            "accounting.transactions",
            "offline_access");

    private static final String SCOPE_DELIMITER = " ";

    private XeroScope() {
    }

    /**
     * The raw, space-delimited {@code scope} value. Deliberately not pre-encoded: the caller runs
     * the whole authorization URI through {@code UriComponentsBuilder.encode()}, and encoding here
     * as well would send Xero percent signs where it expects spaces.
     */
    public static String scopeParameter() {
        return String.join(SCOPE_DELIMITER, SCOPES);
    }
}
