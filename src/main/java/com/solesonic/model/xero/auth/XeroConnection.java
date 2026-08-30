package com.solesonic.model.xero.auth;

/**
 * One entry from {@code GET https://api.xero.com/connections}: a Xero organisation the user granted
 * this app access to.
 * <p>
 * The token exchange alone is not enough to call the Accounting API — {@code tenantId} has to travel
 * on the {@code xero-tenant-id} header of every request, and it is only discoverable here. Xero's
 * consent screen cannot be constrained to a single organisation up front, so a user may return more
 * than one of these.
 * <p>
 * Xero sends {@code authEventId}, {@code createdDateUtc} and {@code updatedDateUtc} alongside these
 * fields; they are dropped rather than modelled, which works because {@code JacksonConfig} disables
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}.
 */
public record XeroConnection(
        String id,
        String tenantId,
        String tenantType,
        String tenantName) {
}
