package com.solesonic.model.a2a;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LiteLlmAgent(
        @JsonProperty("agent_id") String agentId,
        @JsonProperty("agent_name") String agentName) {
}
