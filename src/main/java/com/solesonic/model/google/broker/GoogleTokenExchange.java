package com.solesonic.model.google.broker;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nonnull;

import java.util.UUID;

public record GoogleTokenExchange(
        @Nonnull
        @JsonProperty("subject_token")
        UUID subjectToken) {
}
