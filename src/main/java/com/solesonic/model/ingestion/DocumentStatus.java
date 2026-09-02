package com.solesonic.model.ingestion;

public enum DocumentStatus {
    IN_PROGRESS,
    PREPARING,
    TOKEN_SPLITTING,
    KEYWORD_ENRICHING,
    METADATA_ENRICHING,
    QUEUED,
    COMPLETED,
    FAILED,
    REPLACED
}
