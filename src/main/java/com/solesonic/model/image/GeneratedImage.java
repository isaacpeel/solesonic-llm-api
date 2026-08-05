package com.solesonic.model.image;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * An image produced by the image generation tool, decoded from base64 once at the API boundary and
 * stored here with its provenance.
 * <p>
 * The bytes stop at this table on purpose. The tool hands back roughly two megabytes of base64 per
 * image and stores nothing itself; letting that travel onward — into chat history, into a
 * conversation export, or into the model's context — is what makes images unusable at any scale.
 * Everything downstream carries the id.
 *
 * @see com.solesonic.model.chat.attachment.ChatAttachment the same bytes-in-Postgres shape, for
 * images travelling the other way
 */
@SuppressWarnings("unused")
@Entity
public class GeneratedImage {
    @Id
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID userId;

    /**
     * The conversation this image was generated in, or null when it came from explicit generation,
     * which has no chat.
     */
    private UUID chatId;

    /**
     * The assistant turn that produced it. Null until that message is written — the image exists
     * before the message does, because the tool runs mid-turn.
     */
    private UUID chatMessageId;

    /**
     * The prompt the user supplied, verbatim. Half of the provenance record, and the image's
     * {@code alt} text.
     */
    @Column(columnDefinition = "TEXT")
    private String prompt;

    private String model;

    /**
     * The other half of the provenance record. Chosen randomly by the image server per call, so it
     * is the only thing that identifies one image among many from the same prompt.
     */
    private Long seed;

    private Integer width;

    private Integer height;

    private Integer steps;

    private Double elapsedSeconds;

    /**
     * Digest of {@link #imageData}, served as the strong {@code ETag}. Bytes never change once
     * written, which is what makes the download endpoint's long-lived cache headers safe.
     */
    private String sha256;

    private String contentType;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    private byte[] imageData;

    private long fileSizeBytes;

    private ZonedDateTime created;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public UUID getChatMessageId() {
        return chatMessageId;
    }

    public void setChatMessageId(UUID chatMessageId) {
        this.chatMessageId = chatMessageId;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Long getSeed() {
        return seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Integer getSteps() {
        return steps;
    }

    public void setSteps(Integer steps) {
        this.steps = steps;
    }

    public Double getElapsedSeconds() {
        return elapsedSeconds;
    }

    public void setElapsedSeconds(Double elapsedSeconds) {
        this.elapsedSeconds = elapsedSeconds;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public byte[] getImageData() {
        return imageData;
    }

    public void setImageData(byte[] imageData) {
        this.imageData = imageData;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public ZonedDateTime getCreated() {
        return created;
    }

    public void setCreated(ZonedDateTime created) {
        this.created = created;
    }
}
