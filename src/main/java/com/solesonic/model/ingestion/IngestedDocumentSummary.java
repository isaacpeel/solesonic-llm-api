package com.solesonic.model.ingestion;

import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.rag.DocumentPrincipal;
import com.solesonic.model.rag.PrincipalType;
import com.solesonic.model.rag.RetrievalScope;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a client is told about an ingested document, and the only shape any document collection ever
 * returns.
 * <p>
 * Never the entity: a static factory is what makes including something unintended structurally
 * impossible wherever the conversion happens.
 *
 * @param fileSizeBytes read from the {@link IngestedDocument#FILE_SIZE_BYTES} metadata key rather
 *                      than a column. Zero when the key is absent, which is a URI document that has
 *                      not been fetched yet — its size is not known until the fetch happens
 * @param scope         <strong>deprecated, derived.</strong> Kept so this change does not break the
 *                      UI in the same release as the model change. It is computed from the retrieve
 *                      grants and is faithful only while a document has exactly one — the moment
 *                      sharing ships it is lossy by construction. Read {@code entitlements} instead
 * @param chatId        the conversation this document came from, read from provenance metadata
 *                      rather than a column. Unchanged on the wire
 * @param entitlements  who may retrieve this document, as principal keys — {@code ["chat:…"]},
 *                      {@code ["user:…"]}, {@code ["global"]}. The forward-looking field, and the
 *                      only one that stays correct once a document can be granted to more than one
 *                      principal
 * @param chatName      the conversation's name, where one is known. A cross-chat listing shows a
 *                      bare id today and cannot label its rows
 */
public record IngestedDocumentSummary(UUID id,
                                      String fileName,
                                      String contentType,
                                      long fileSizeBytes,
                                      DocumentSource documentSource,
                                      RetrievalScope scope,
                                      UUID chatId,
                                      List<String> entitlements,
                                      String chatName,
                                      DocumentStatus documentStatus,
                                      ZonedDateTime created,
                                      ZonedDateTime updated) {

    /**
     * The one way an {@link IngestedDocument} becomes something a client may see.
     * <p>
     * {@code documentStatus} and {@code retrievePrincipals} are passed in because neither is on the
     * row: the first is derived from {@code status_history} and the second from
     * {@code document_entitlement}, and how each was looked up — per document, or batched for a
     * page — is the caller's business.
     */
    public static IngestedDocumentSummary of(IngestedDocument ingestedDocument,
                                             DocumentStatus documentStatus,
                                             List<DocumentPrincipal> retrievePrincipals,
                                             String chatName) {
        return new IngestedDocumentSummary(ingestedDocument.getId(),
                ingestedDocument.getFileName(),
                ingestedDocument.getContentType(),
                fileSizeBytes(ingestedDocument),
                ingestedDocument.getDocumentSource(),
                derivedScope(retrievePrincipals),
                chatId(ingestedDocument),
                retrievePrincipals.stream().map(DocumentPrincipal::key).toList(),
                chatName,
                documentStatus,
                ingestedDocument.getCreated(),
                ingestedDocument.getUpdated());
    }

    public static IngestedDocumentSummary of(IngestedDocument ingestedDocument,
                                             DocumentStatus documentStatus,
                                             List<DocumentPrincipal> retrievePrincipals) {
        return of(ingestedDocument, documentStatus, retrievePrincipals, null);
    }

    /**
     * The narrowest audience the document is granted to, as the old single-valued field.
     * <p>
     * A stopgap, and recorded as one. It exists so a client reading {@code scope} keeps working
     * across this change; a document granted to several principals cannot be described by one value,
     * so the narrowest is reported and the field is retired once clients read {@code entitlements}.
     * Narrowest first is the honest choice of the three: it understates reach rather than
     * overstating it, and a UI badging a shared document "chat" is a smaller wrong than one badging
     * it "global".
     */
    private static RetrievalScope derivedScope(List<DocumentPrincipal> retrievePrincipals) {
        if (retrievePrincipals.stream().anyMatch(principal -> principal.type() == PrincipalType.CHAT)) {
            return RetrievalScope.CHAT;
        }

        if (retrievePrincipals.stream().anyMatch(principal -> principal.type() == PrincipalType.USER)) {
            return RetrievalScope.USER;
        }

        if (retrievePrincipals.stream().anyMatch(principal -> principal.type() == PrincipalType.GLOBAL)) {
            return RetrievalScope.GLOBAL;
        }

        return null;
    }

    /**
     * Read from provenance metadata rather than a column, which is where it always actually lived —
     * the column {@code V3_26} added existed only so a JPQL projection could filter on it.
     */
    private static UUID chatId(IngestedDocument ingestedDocument) {
        Map<String, Object> metadata = ingestedDocument.getMetadata();

        if (metadata == null) {
            return null;
        }

        Object chatId = metadata.get(IngestedDocument.CHAT_ID);

        return chatId == null ? null : UUID.fromString(chatId.toString());
    }

    /**
     * The size lives in the metadata map rather than a column. A URI document has none until its
     * content is fetched, which is a zero rather than a failure.
     */
    private static long fileSizeBytes(IngestedDocument ingestedDocument) {
        Map<String, Object> metadata = ingestedDocument.getMetadata();

        if (metadata == null) {
            return 0L;
        }

        if (metadata.get(IngestedDocument.FILE_SIZE_BYTES) instanceof Number fileSizeBytes) {
            return fileSizeBytes.longValue();
        }

        return 0L;
    }
}
