package com.solesonic.izzybot.model.training;

public enum DocumentStatus {
    IN_PROGRESS,
    PREPARING,
    KEYWORD_ENRICHING,
    METADATA_ENRICHING,
    TOKEN_SPLITTING,
    QUEUED,
    COMPLETED,
    FAILED,
    REPLACED
}
