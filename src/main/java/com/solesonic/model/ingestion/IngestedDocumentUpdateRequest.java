package com.solesonic.model.ingestion;

/**
 * The whole of what an update may write. Rename only — re-running extraction over the existing
 * content is {@code refresh}'s job, and replacing the content outright is not an operation either
 * document collection offers.
 */
public record IngestedDocumentUpdateRequest(String fileName) {
}
