package com.solesonic.model.rag;

/**
 * The client-facing shape of a document/URI ingestion failure that reached a REST endpoint directly
 * — as opposed to mid-chat-turn, where the same exception types are classified onto
 * {@code TurnErrorCode} instead. Both {@link com.solesonic.exception.ChatException} and
 * {@link com.solesonic.exception.rag.DocumentReadException} already carry a message vetted as
 * user-safe at every throw site, so it is returned as-is rather than replaced with a generic one.
 */
public record IngestionFailure(String message) {
}
