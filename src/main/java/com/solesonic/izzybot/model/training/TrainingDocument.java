package com.solesonic.izzybot.model.training;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.solesonic.izzybot.model.document.DocumentSource;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
public class TrainingDocument {
    public static final String REPLACED_BY_ID = "REPLACED_BY_ID";
    public static final String CONFLUENCE_PAGE_VERSION = "CONFLUENCE_PAGE_VERSION";
    public static final String CONFLUENCE_PAGE_ID = "CONFLUENCE_PAGE_ID";

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

    @Column(name = "metadata")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata;

    public TrainingDocument() {
    }

    public TrainingDocument(UUID id, String fileName, String contentType) {
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

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
