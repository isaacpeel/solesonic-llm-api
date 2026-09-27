package com.solesonic.model.rag;

/**
 * What a {@link DocumentPrincipal} may do with a document.
 * <p>
 * Splitting these apart is what makes "every document I have ever uploaded to any conversation" a
 * single indexed query. Before, one fact carried both meanings: a {@code CHAT} document was
 * retrievable in its conversation <em>and</em> belonged to whoever sent it, with no way to ask the
 * second question without joining through {@code chat} to find the caller's conversations first.
 */
public enum GrantKind {
    /**
     * This document's chunks may come back from a search performed for this principal.
     * <p>
     * The only kind that projects outward into chunk metadata
     * ({@code DocumentEntitlementService.retrievalKeys}). Retrieval is the only question the vector
     * store is asked, so it is the only entitlement it needs to know about.
     */
    RETRIEVE,

    /**
     * This principal may list, rename, refresh, promote and delete the document.
     * <p>
     * Deliberately never reaches chunk metadata: management is not a retrieval concept, and keeping
     * it out is what stops the chunk-side array growing keys no filter will ever compare against.
     * <p>
     * Independent of {@link #RETRIEVE}. A chat document is retrievable by the conversation but
     * managed by the person who uploaded it, and promoting it to the shared corpus replaces its
     * retrieve grant while leaving management alone.
     */
    MANAGE
}
