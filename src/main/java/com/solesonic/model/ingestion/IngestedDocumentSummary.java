package com.solesonic.model.ingestion;

import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.rag.RetrievalScope;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * What a client is told about an ingested document, and the only shape either document collection
 * ever returns.
 * <p>
 * Never the entity: {@link IngestedDocument} carries {@code fileData}, and a controller serializing
 * it would put the whole uploaded file on the wire. The same reason
 * {@link com.solesonic.model.chat.attachment.ChatAttachmentSummary} exists.
 *
 * @param fileSizeBytes read from the {@link IngestedDocument#FILE_SIZE_BYTES} metadata key rather
 *                      than a column. Zero when the key is absent, which is a URI document that has
 *                      not been fetched yet — its size is not known until the fetch happens
 * @param scope         kept even though a caller can infer it from the collection it asked. Cheaper
 *                      than making every client reconstruct it, and it is what the field on
 *                      {@link IngestedDocument} is for
 * @param chatId        the conversation a {@code CHAT} document belongs to, null at every other
 *                      scope. Carried for the same reason {@code scope} is
 */
public record IngestedDocumentSummary(UUID id,
                                      String fileName,
                                      String contentType,
                                      long fileSizeBytes,
                                      DocumentSource documentSource,
                                      RetrievalScope scope,
                                      UUID chatId,
                                      DocumentStatus documentStatus,
                                      ZonedDateTime created,
                                      ZonedDateTime updated) {

    /**
     * The one way an {@link IngestedDocument} becomes something a client may see. A static factory
     * rather than a mapping each caller writes: {@code fileData} is then structurally impossible to
     * include, wherever the conversion happens.
     * <p>
     * {@code documentStatus} is passed in because it is not on the row — it is derived from
     * {@code status_history}, and how it was looked up (per document, or batched for a page) is the
     * caller's business.
     */
    public static IngestedDocumentSummary of(IngestedDocument ingestedDocument, DocumentStatus documentStatus) {
        return new IngestedDocumentSummary(ingestedDocument.getId(),
                ingestedDocument.getFileName(),
                ingestedDocument.getContentType(),
                fileSizeBytes(ingestedDocument),
                ingestedDocument.getDocumentSource(),
                ingestedDocument.getScope(),
                ingestedDocument.getChatId(),
                documentStatus,
                ingestedDocument.getCreated(),
                ingestedDocument.getUpdated());
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
