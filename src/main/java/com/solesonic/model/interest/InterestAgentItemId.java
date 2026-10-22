package com.solesonic.model.interest;

import java.io.Serializable;
import java.util.UUID;

public record InterestAgentItemId(UUID agentId, String urlHash) implements Serializable {
}
