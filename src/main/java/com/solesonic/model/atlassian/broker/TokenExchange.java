package com.solesonic.model.atlassian.broker;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.NotNull;

import java.util.UUID;

public record TokenExchange(
        @NotNull
        @JsonProperty("subject_token")
        UUID subjectToken,
        String audience) {
}