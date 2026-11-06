package com.solesonic.model.user;

import java.util.UUID;

/**
 * Who a user id belongs to, as their identity provider last described them: {@code username} is
 * {@code preferred_username}, {@code displayName} is {@code given_name} or else {@code name}. Any of
 * the three may be null, since a token need not carry them.
 */
public record UserProfile(UUID userId, String username, String email, String displayName) {

    public boolean isEmpty() {
        return username == null && email == null && displayName == null;
    }
}
