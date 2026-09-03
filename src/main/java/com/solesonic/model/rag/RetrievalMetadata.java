package com.solesonic.model.rag;

/**
 * The metadata keys that decide which vector store chunks a search can return, and where each chunk
 * came from.
 * <p>
 * These are written into the pgvector {@code metadata} column by every ingestion path and compared
 * against by every retrieval filter, which is the only reason a single shared table can serve
 * global, per-user and per-conversation material at once. A key written under one name and filtered
 * under another silently retrieves nothing, so both sides read them from here.
 * <p>
 * The keys divide into two kinds, and keeping them apart is the point:
 * <ul>
 *   <li><strong>Entitlement</strong> — {@link #ENTITLEMENTS} alone. Who may retrieve this chunk.</li>
 *   <li><strong>Provenance</strong> — {@link #CHAT_ID}, {@link #CHAT_ATTACHMENT_ID}. Where the chunk
 *       came from, which is what teardown matches on and what survives a document being promoted
 *       out of its origin conversation.</li>
 * </ul>
 */
public final class RetrievalMetadata {

    /**
     * Every principal this chunk may be retrieved by, as a JSON array of
     * {@link DocumentPrincipal#key()} strings — {@code ["chat:9f2c…"]}, {@code ["user:abc"]},
     * {@code ["global"]}.
     * <p>
     * Replaces the old {@code scope} and {@code userId} pair, which could express exactly one
     * audience per chunk. An array can express several, so sharing a document with a second
     * principal later is another element rather than another column.
     * <p>
     * A filter compares against this with a plain equality, not a containment operator.
     * {@code PgVectorFilterExpressionConverter} emits a jsonpath predicate —
     * {@code metadata::jsonb @@ '$."entitlements" == "user:abc"'::jsonpath} — and Postgres evaluates
     * {@code @@} in lax mode, which auto-unwraps the array. So {@code eq(ENTITLEMENTS, key)} matches
     * a chunk holding that key among others, with no custom converter and no Spring AI fork.
     * <p>
     * Written from exactly one place, {@code DocumentEntitlementService.retrievalKeys(...)}, so this
     * array is a projection of the {@code document_entitlement} rows rather than a second source of
     * truth that can drift from them.
     * <p>
     * Present on every chunk. A filter never matches an absent key, so a chunk written without this
     * is retrievable by nobody.
     */
    public static final String ENTITLEMENTS = "entitlements";

    /**
     * The conversation a chunk came from — <em>provenance, not entitlement</em>.
     * <p>
     * These two used to be the same value doing two jobs, which is why neither could change without
     * the other. Now {@code CHAT_ID} says where the chunk originated and {@link #ENTITLEMENTS} says
     * who may retrieve it, so promoting a document out of a conversation changes the second and
     * leaves the first as an audit trail.
     * <p>
     * What still reads it is teardown: {@code VectorStoreRepository.deleteByChatId} matches on it, so
     * deleting a conversation still takes the chunks that came from it.
     */
    public static final String CHAT_ID = "chatId";

    /**
     * The {@code chat_attachment} row a chunk was extracted from, so deleting one attachment can
     * delete exactly its own chunks. Provenance, like {@link #CHAT_ID}.
     */
    public static final String CHAT_ATTACHMENT_ID = "chatAttachmentId";

    /**
     * Original file name, carried so a retrieved chunk can say which document it came from.
     */
    public static final String FILE_NAME = "fileName";

    private RetrievalMetadata() {
    }
}
