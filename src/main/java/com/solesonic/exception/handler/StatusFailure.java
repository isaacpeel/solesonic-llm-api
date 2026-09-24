package com.solesonic.exception.handler;

/**
 * The client-facing body for a plain {@link org.springframework.web.server.ResponseStatusException}
 * — a {@code reason} that was already written for a caller by whichever service threw it, so it is
 * returned as-is rather than replaced with a generic one. Not a domain type: this exception is
 * thrown across unrelated domains (attachments, ingestion, promotion conflicts), so no single
 * domain's response record fits every caller.
 */
public record StatusFailure(String message) {
}
