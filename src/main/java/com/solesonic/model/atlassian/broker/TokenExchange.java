package com.solesonic.model.atlassian.broker;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nonnull;

import java.util.UUID;

public record TokenExchange(
        @Nonnull
        @JsonProperty("subject_token")
        UUID subjectToken,
        String audience) {
}