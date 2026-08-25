package com.solesonic.model.rag;

/**
 * The metadata keys that make a vector store chunk retrievable at a given scope.
 * <p>
 * These are written into the pgvector {@code metadata} column by every ingestion path and compared
 * against by every retrieval filter, which is the only reason a single shared table can serve
 * global, per-user and per-conversation material at once. A key written under one name and filtered
 * under another silently retrieves nothing, so both sides read them from here.
 */
public final class RetrievalMetadata {

    /**
     * The {@link RetrievalScope}, by name. Present on every chunk — rows predating scoping were
     * backfilled to {@code GLOBAL}, because a filter never matches a key that is absent.
     */
    public static final String SCOPE = "scope";

    /**
     * Owner of a {@code USER} or {@code CHAT} scoped chunk. Absent on {@code GLOBAL} chunks.
     */
    public static final String USER_ID = "userId";

    /**
     * The conversation a {@code CHAT} scoped chunk belongs to. Absent at every other scope.
     */
    public static final String CHAT_ID = "chatId";

    /**
     * The {@code chat_attachment} row a {@code CHAT} scoped chunk was extracted from, so deleting
     * one attachment can delete exactly its own chunks.
     */
    public static final String CHAT_ATTACHMENT_ID = "chatAttachmentId";

    /**
     * Original file name, carried so a retrieved chunk can say which document it came from.
     */
    public static final String FILE_NAME = "fileName";

    private RetrievalMetadata() {
    }
}
