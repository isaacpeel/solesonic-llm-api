package com.solesonic.model.rag;

import java.util.UUID;

/**
 * Who a document entitlement is granted to: a {@link PrincipalType} and the id of the one thing of
 * that type.
 * <p>
 * The id is a {@code String} rather than a {@code UUID} because not every principal is identified by
 * one — {@code GLOBAL} has no owner at all and {@code ROLE} is named. Keeping one shape means the
 * grant table needs one column rather than one per identifier type, which is the same reason
 * {@code principal_id} is {@code varchar} in the schema.
 *
 * @param type what kind of thing this is
 * @param id   which one. Never null and never blank — see {@link #GLOBAL_SENTINEL}
 */
public record DocumentPrincipal(PrincipalType type, String id) {

    /**
     * The principal id a {@code GLOBAL} grant carries, since there is no owner to name.
     * <p>
     * A sentinel rather than null on purpose. {@code document_entitlement} is uniquely keyed on
     * {@code (document, principal_type, principal_id, grant_kind)}, and in SQL no two nulls compare
     * equal — so a null here would let the same document be granted to everyone twice, which is
     * precisely the duplicate the constraint exists to reject.
     */
    public static final String GLOBAL_SENTINEL = "-";

    /**
     * The only role this codebase grants. Matches the Spring Security role checked by
     * {@code @PreAuthorize} on the global document endpoints, so the two cannot drift.
     */
    public static final String RAG_ADMIN_ROLE = "rag-admin";

    private static final DocumentPrincipal GLOBAL = new DocumentPrincipal(PrincipalType.GLOBAL, GLOBAL_SENTINEL);
    private static final DocumentPrincipal RAG_ADMIN = new DocumentPrincipal(PrincipalType.ROLE, RAG_ADMIN_ROLE);

    public DocumentPrincipal {
        if (type == null) {
            throw new IllegalArgumentException("A document principal needs a type");
        }

        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A document principal needs an id; GLOBAL uses GLOBAL_SENTINEL");
        }
    }

    /**
     * The shared corpus. One instance, because it carries no identity to vary.
     */
    public static DocumentPrincipal global() {
        return GLOBAL;
    }

    public static DocumentPrincipal user(UUID userId) {
        return new DocumentPrincipal(PrincipalType.USER, userId.toString());
    }

    public static DocumentPrincipal chat(UUID chatId) {
        return new DocumentPrincipal(PrincipalType.CHAT, chatId.toString());
    }

    /**
     * The role that manages the shared corpus, and the only {@code MANAGE} grant a global document
     * carries.
     */
    public static DocumentPrincipal ragAdmin() {
        return RAG_ADMIN;
    }

    /**
     * How this principal appears in a chunk's {@code entitlements} metadata array, and therefore
     * what a retrieval filter compares against literally.
     * <p>
     * Written and read from this one method so the two sides cannot disagree — a key written under
     * one spelling and filtered under another retrieves nothing, silently, which is the failure mode
     * {@code V3_14} existed to repair.
     * <p>
     * {@code GLOBAL} is a bare {@code "global"} with no id: the sentinel is a storage detail of the
     * unique constraint and has no business on a chunk.
     */
    public String key() {
        return switch (type) {
            case GLOBAL -> "global";
            case USER -> "user:" + id;
            case CHAT -> "chat:" + id;
            case ROLE -> "role:" + id;
        };
    }
}
