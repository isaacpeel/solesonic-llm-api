package com.solesonic.model.rag;

/**
 * How widely a chunk in the vector store may be retrieved.
 * <p>
 * Stored as the {@code scope} key of the pgvector {@code metadata} column and filtered on at query
 * time, so a chunk's reach is a property of the chunk itself rather than of which table it landed
 * in. Serialized by name: the filter expressions in {@link com.solesonic.service.rag.ScopedDocumentRetriever}
 * compare against these constants literally, so renaming one is a data migration.
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
