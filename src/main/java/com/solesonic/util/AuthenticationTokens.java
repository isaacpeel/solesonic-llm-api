package com.solesonic.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Takes the bearer token back off an authenticated request, for the routes that have to forward the
 * user's own identity onward rather than act as the service.
 * <p>
 * The cast is a real check, not a formality: every caller here is about to hand the value to a
 * downstream MCP server or remote agent, so a principal that is not a JWT has to fail loudly rather
 * than send a null token that fails as an opaque 401 several hops away.
 */
public final class AuthenticationTokens {

    private AuthenticationTokens() {
    }

    /**
     * @return the raw token value of the request's JWT principal
     * @throws IllegalStateException when the principal is not a JWT
     */
    public static String token(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Authentication principal is not a JWT token");
        }

        return jwt.getTokenValue();
    }
}
