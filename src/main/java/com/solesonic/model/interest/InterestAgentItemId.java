package com.solesonic.model.interest;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class InterestAgentItemId implements Serializable {
    private UUID agentId;

    private String urlHash;

    public UUID getAgentId() {
        return agentId;
    }

    public String getUrlHash() {
        return urlHash;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof InterestAgentItemId that)) {
            return false;
        }

        return Objects.equals(agentId, that.agentId) && Objects.equals(urlHash, that.urlHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(agentId, urlHash);
    }
}
