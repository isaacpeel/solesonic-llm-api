package com.solesonic.model.ingestion;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * The uploaded bytes of one ingested document, off the document row.
 * <p>
 * Splitting these out is what lets a listing select whole entities: a page of twenty documents used
 * to be a page of twenty uploaded files, which is why the scoped listings projected through a JPQL
 * constructor expression — and why they could only filter on columns, JPQL being unable to index
 * into the json metadata column. With the bytes gone the projection is gone, and a listing is free
 * to be a native query joining {@code document_entitlement}.
 * <p>
 * It also retires the documented hazard that a projected instance handed to {@code save} wrote its
 * unloaded {@code fileData} back as null, and makes every status and metadata update cheap instead
 * of rewriting megabytes.
 * <p>
 * <strong>Never {@code @Lob}.</strong> That annotation is what made
 * {@code IngestedDocument.fileData} a Postgres large object — an {@code oid} reference whose target
 * survives the deletion of the row pointing at it unless {@code lo_unlink} is called, which nothing
 * here ever did. By the time {@code V3_27} swept them up there were 351,476 orphaned objects
 * totalling some 6 GB. {@link SqlTypes#VARBINARY} maps to {@code bytea}, which a row delete reclaims
 * by itself; {@code ChatAttachment} made the same choice for the same reason.
 * <p>
 * A document has no row here when its bytes live elsewhere — a {@code URI} document before its
 * fetch, and a chat attachment whose only copy is on {@code chat_attachment}. Absence is that state,
 * where an empty {@code byte[0]} used to stand in for it.
 */
@Entity
public class IngestedDocumentContent {

    /**
     * Shares the document's id: this is a 1:1 owned by {@code ingested_document}, so the primary key
     * is the foreign key and no second identifier can drift from it.
     */
    @Id
    private UUID ingestedDocumentId;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    private byte[] data;

    private long sizeBytes;

    public IngestedDocumentContent() {
    }

    public IngestedDocumentContent(UUID ingestedDocumentId, byte[] data) {
        this.ingestedDocumentId = ingestedDocumentId;
        this.data = data;
        this.sizeBytes = data == null ? 0L : data.length;
    }

    public UUID getIngestedDocumentId() {
        return ingestedDocumentId;
    }

    public void setIngestedDocumentId(UUID ingestedDocumentId) {
        this.ingestedDocumentId = ingestedDocumentId;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
        this.sizeBytes = data == null ? 0L : data.length;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }
}
