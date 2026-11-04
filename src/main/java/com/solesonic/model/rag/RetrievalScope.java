package com.solesonic.model.rag;

/**
 * How widely a document may be retrieved, as a label for clients.
 * <p>
 * No longer stored anywhere. Chunks used to carry it as a {@code scope} metadata key, which
 * {@code V3_27} replaced with the {@code entitlements} array ({@link PrincipalType} is the storage
 * and filtering vocabulary now). It survives only as {@code IngestedDocumentSummary.scope}, derived
 * from a document's retrieve grants, so API responses keep their shape. Serialized by name in those
 * responses, so renaming a constant is an API change.
 */
public enum RetrievalScope {
    /**
     * Shared ingested material. Retrievable by every user, in every conversation — the only scope
     * that existed before scoping, and what every pre-existing row is backfilled to.
     */
    GLOBAL,

    /**
     * Owned by one user. Retrievable in every conversation of that user and no one else's.
     */
    USER,

    /**
     * Owned by one conversation — a document attached to a chat message. Retrievable only while
     * answering in that chat.
     */
    CHAT
}
