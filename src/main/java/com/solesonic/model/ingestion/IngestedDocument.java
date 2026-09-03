package com.solesonic.model.ingestion;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.solesonic.model.document.DocumentSource;
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
     * The conversation this document was attached to, and the {@code chat_attachment} row it was read
     * from. Named after {@link com.solesonic.model.rag.RetrievalMetadata#CHAT_ID} and
     * {@link com.solesonic.model.rag.RetrievalMetadata#CHAT_ATTACHMENT_ID}, but a separate map: those
     * are chunk metadata, these are entity metadata.
     * <p>
     * Both are <em>provenance</em> — where this row came from — and after the entitlement model they
     * are nothing else. Who may retrieve the document is {@code document_entitlement}'s answer, so a
     * document promoted out of a conversation keeps these keys as an audit trail and simply stops
     * being granted to that chat. That separation is what lets teardown stay keyed on provenance:
     * deleting the conversation still finds every row that came from it.
     */
    public static final String CHAT_ID = "CHAT_ID";
    public static final String CHAT_ATTACHMENT_ID = "CHAT_ATTACHMENT_ID";

    @Id
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String fileName;

    private String contentType;

    @Transient
    private DocumentStatus documentStatus;

    private ZonedDateTime created;

    private ZonedDateTime updated;

    @Enumerated(EnumType.STRING)
    private DocumentSource documentSource;

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

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
