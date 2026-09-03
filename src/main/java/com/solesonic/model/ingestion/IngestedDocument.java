package com.solesonic.model.ingestion;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.rag.RetrievalScope;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
public class IngestedDocument {
    public static final String REPLACED_BY_ID = "REPLACED_BY_ID";
    public static final String CONFLUENCE_PAGE_VERSION = "CONFLUENCE_PAGE_VERSION";
    public static final String CONFLUENCE_PAGE_ID = "CONFLUENCE_PAGE_ID";
    public static final String ORIGINAL_FILE_NAME = "ORIGINAL_FILE_NAME";
    public static final String FILE_SIZE_BYTES = "FILE_SIZE_BYTES";
    public static final String SOURCE_URI = "SOURCE_URI";

    /**
     * The conversation a {@code CHAT} scoped document was attached to, and the
     * {@code chat_attachment} row it was read from. Named after
     * {@link com.solesonic.model.rag.RetrievalMetadata#CHAT_ID} and
     * {@link com.solesonic.model.rag.RetrievalMetadata#CHAT_ATTACHMENT_ID}, but a separate map: those
     * are chunk metadata a retrieval filter reads, these are entity metadata that says where this row
     * came from, so deleting either the attachment or the whole chat can find it again.
     */
    public static final String CHAT_ID = "CHAT_ID";
    public static final String CHAT_ATTACHMENT_ID = "CHAT_ATTACHMENT_ID";

    @Id
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String fileName;

    private String contentType;

    @Lob
    private byte[] fileData;

    @Transient
    private DocumentStatus documentStatus;

    private ZonedDateTime created;

    private ZonedDateTime updated;

    @Enumerated(EnumType.STRING)
    private DocumentSource documentSource;

    /**
     * Who owns this document, when {@link #scope} is {@code USER}. Null at {@code GLOBAL} scope,
     * which is what every document ingested before scoping existed is.
     */
    private UUID userId;

    /**
     * How widely the chunks of this document may be retrieved. Stamped onto every chunk's metadata
     * at ingestion, which is where retrieval actually reads it from — this column is the record of
     * what was intended, so a re-ingest reproduces the same scope.
     */
    @Enumerated(EnumType.STRING)
    private RetrievalScope scope;

    /**
     * Which conversation this document belongs to, when {@link #scope} is {@code CHAT}. Null at every
     * other scope, the way {@link #userId} is null at {@code GLOBAL}.
     * <p>
     * A column rather than only the {@link #CHAT_ID} metadata key because the {@code CHAT} collection
     * pages and looks up by it, and both of those project {@code fileData} away — which is JPQL, and
     * JPQL cannot index into the json {@code metadata} column. The key is still written and still
     * read: it is what the teardown queries and the chunk stamping use.
     */
    private UUID chatId;

    @Column(name = "metadata")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata;

    public IngestedDocument() {
    }

    public IngestedDocument(UUID id, String fileName, String contentType) {
        this.id = id;
        this.fileName = fileName;
        this.contentType = contentType;
    }

    /**
     * Everything a {@link IngestedDocumentSummary} needs and nothing else, so that the scoped
     * listing queries can project rows without loading {@code fileData}. A whole row is on the
     * order of the uploaded file; a page of twenty of them is not something to read to answer a
     * listing.
     * <p>
     * {@code documentStatus} is absent on purpose — it is {@link Transient}, derived from
     * {@code status_history}, and the service fills it in after the projection.
     */
    public IngestedDocument(UUID id,
                            String fileName,
                            String contentType,
                            DocumentSource documentSource,
                            RetrievalScope scope,
                            UUID userId,
                            UUID chatId,
                            Map<String, Object> metadata,
                            ZonedDateTime created,
                            ZonedDateTime updated) {
        this.id = id;
        this.fileName = fileName;
        this.contentType = contentType;
        this.documentSource = documentSource;
        this.scope = scope;
        this.userId = userId;
        this.chatId = chatId;
        this.metadata = metadata;
        this.created = created;
        this.updated = updated;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String name) {
        this.fileName = name;
    }

    public byte[] getFileData() {
        return fileData;
    }

    public void setFileData(byte[] fileData) {
        this.fileData = fileData;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public DocumentStatus getDocumentStatus() {
        return documentStatus;
    }

    public void setDocumentStatus(DocumentStatus documentStatus) {
        this.documentStatus = documentStatus;
    }

    public ZonedDateTime getCreated() {
        return created;
    }

    public void setCreated(ZonedDateTime created) {
        this.created = created;
    }

    public ZonedDateTime getUpdated() {
        return updated;
    }

    public void setUpdated(ZonedDateTime updated) {
        this.updated = updated;
    }

    public DocumentSource getDocumentSource() {
        return documentSource;
    }

    public void setDocumentSource(DocumentSource documentSource) {
        this.documentSource = documentSource;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getChatId() {
        return chatId;
    }

    public void setChatId(UUID chatId) {
        this.chatId = chatId;
    }

    public RetrievalScope getScope() {
        return scope;
    }

    public void setScope(RetrievalScope scope) {
        this.scope = scope;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
