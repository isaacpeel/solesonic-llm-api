package com.solesonic.model.chat.attachment;

/**
 * Why a document attachment was not indexed for retrieval.
 * <p>
 * A closed set, for the same reason {@link VisionFailureReason} is one: the frontend maps each
 * constant to its own copy, so adding a constant is an API change that needs a matching change
 * there. Serialized by name, both on the {@code attachment} SSE frame and on
 * {@link ChatAttachmentSummary} in chat history.
 */
public enum ExtractionFailureReason {
    /**
     * The document is larger than {@code solesonic.llm.attachment.document.max-size-bytes}.
     */
    DOCUMENT_TOO_LARGE,

    /**
     * The file could not be parsed, or parsed to nothing a model could read — an encrypted PDF, a
     * corrupt archive, or a scanned page carrying images rather than text.
     */
    DOCUMENT_UNREADABLE,

    /**
     * The embedding model could not be reached, so the extracted text could not be indexed.
     */
    EMBEDDING_UNAVAILABLE,

    /**
     * More documents were attached to one message than the extraction pass will index.
     */
    EXCEEDED_DOCUMENT_LIMIT
}
