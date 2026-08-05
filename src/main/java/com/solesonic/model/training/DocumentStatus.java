package com.solesonic.model.training;

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
