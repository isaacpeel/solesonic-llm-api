package com.solesonic.model.interest;

/**
 * The body of every failed interest request. {@code message} is a fixed, user-safe sentence: it never
 * carries a label, a query or an exception string.
 */
public record InterestErrorResponse(InterestErrorCode code, String message) {
}
