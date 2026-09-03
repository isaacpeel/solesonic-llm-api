package com.solesonic.model.ingestion;

import java.util.UUID;

/**
 * One document's latest status, as returned for a whole page of documents at once.
 * <p>
 * Exists so that rendering a page of {@link IngestedDocumentSummary} costs one status query rather
 * than one per row — which is what listing did before it was paginated.
 */
public record DocumentStatusEntry(UUID documentId, DocumentStatus documentStatus) {
}
