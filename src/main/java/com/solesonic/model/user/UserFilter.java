package com.solesonic.model.user;

import java.util.UUID;

/**
 * Who a cross-user listing is narrowed to: one user, everyone ({@code userId} null), or nobody,
 * when a username matched no recorded user.
 */
public record UserFilter(UUID userId, boolean matchesNobody) {
    public static final UserFilter EVERYONE = new UserFilter(null, false);
    public static final UserFilter NOBODY = new UserFilter(null, true);

    public static UserFilter of(UUID userId) {
        return userId == null ? EVERYONE : new UserFilter(userId, false);
    }
}
