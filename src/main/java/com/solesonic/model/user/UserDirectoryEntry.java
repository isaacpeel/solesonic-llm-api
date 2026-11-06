package com.solesonic.model.user;

import java.util.UUID;

/**
 * A user as the directory shows them to everyone else: never the email.
 */
public record UserDirectoryEntry(UUID userId, String username, String displayName) {
}
