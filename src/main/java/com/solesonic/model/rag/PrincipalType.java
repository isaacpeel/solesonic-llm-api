package com.solesonic.model.rag;

/**
 * What kind of thing a document entitlement is granted to.
 * <p>
 * This replaces {@link RetrievalScope} as the storage and filtering vocabulary. The difference is
 * not cosmetic: a scope was a property of the <em>document</em>, of which it had exactly one, so
 * widening reach meant changing the document. A principal is the other end of a grant, of which a
 * document may have any number, so widening reach means adding a row.
 * <p>
 * Adding a constant here — {@code TEAM}, {@code ORG} — costs no schema change at all:
 * {@code document_entitlement.principal_type} is text and {@code principal_id} is text. What it does
 * still cost is a retrieval tier, because tiers are a ranking decision rather than a storage one
 * (see {@code VectorStoreService}). That is the honest limit of "flexible" here.
 * <p>
 * Serialized by name into {@code document_entitlement.principal_type} and, via
 * {@link DocumentPrincipal#key()}, into the chunk metadata every retrieval filter compares against.
 * Renaming a constant is therefore a data migration, exactly as it was for {@link RetrievalScope}.
 */
public enum PrincipalType {
    /**
     * Everyone. The shared corpus, retrievable by every user in every conversation.
     * <p>
     * Has no owner, so a {@code GLOBAL} grant carries the {@link DocumentPrincipal#GLOBAL_SENTINEL}
     * as its principal id rather than null — a null would make the unique constraint stop working,
     * since in SQL no two nulls are equal and a document could then be granted to everyone twice.
     */
    GLOBAL,

    /**
     * One user, identified by their user id.
     */
    USER,

    /**
     * One conversation, identified by its chat id.
     */
    CHAT,

    /**
     * A named role rather than a person — {@code rag-admin} being the only one used today, as the
     * sole {@code MANAGE} grant on a global document. The uploading administrator deliberately keeps
     * no personal grant; {@code document_entitlement.granted_by} is what records who added it.
     */
    ROLE
}
