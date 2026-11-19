package com.solesonic.model.user;

/**
 * Body of {@code PATCH /users/{userId}/preferences}. Only non-null fields are applied, so a client
 * can change one setting without sending (and risking nulling) the rest of the preferences.
 */
public record UserPreferencesPatch(String timeZone) {
}
