package com.solesonic.config.user;

/**
 * @param enabled    whether {@code GET /users/directory} answers at all
 * @param maxResults the most entries one search returns
 */
public record UserDirectorySettings(boolean enabled, int maxResults) {
}
