package com.solesonic.service.google;

import java.util.List;

/**
 * Gmail scopes requested during consent.
 * <p>
 * All three are Google <em>restricted</em> scopes. While the OAuth consent screen is in Testing
 * mode they work for the listed test users; publishing the app to general users additionally
 * requires passing a CASA security assessment. Adding a scope here means re-consent — Google only
 * grants what the user approved, and an existing refresh token does not widen retroactively.
 */
public class GoogleScope {

    public static final List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.send",
            "https://www.googleapis.com/auth/gmail.modify");

    private static final String SCOPE_DELIMITER = " ";

    private GoogleScope() {
    }

    /**
     * The raw, space-delimited {@code scope} value. Deliberately not pre-encoded: the caller runs
     * the whole authorization URI through {@code UriComponentsBuilder.encode()}, and encoding here
     * as well would send Google percent signs where it expects colons.
     */
    public static String scopeParameter() {
        return String.join(SCOPE_DELIMITER, SCOPES);
    }
}
