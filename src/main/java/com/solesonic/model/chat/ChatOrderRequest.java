package com.solesonic.model.chat;

/**
 * Where a conversation should sit in the list it was moved within.
 *
 * @param position zero-based index among the conversations that have been placed by hand, or
 *                 {@code null} to unplace this one and let it fall back to timestamp ordering.
 *                 A position past the end of the placed list appends rather than failing — a
 *                 client dragging into the timestamp-ordered part of the list means "last", and
 *                 there is nothing to be gained by rejecting it.
 */
public record ChatOrderRequest(Integer position) {
}
