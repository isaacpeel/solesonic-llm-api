package com.solesonic.service.user;

/**
 * What one lifecycle sweep did, in counts only.
 *
 * @param keycloakReachable false when the realm could not be verified, in which case nobody was
 *                          checked and nobody was marked removed
 */
public record UserLifecycleRun(boolean keycloakReachable, int checked, int removed, int purged, int failures) {
}
