package com.solesonic.model.chat.group;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * A user-owned section a conversation can be filed under.
 * <p>
 * {@code userId} and {@code timestamp} are read-only on the wire for the same reason the id is:
 * ownership comes from the JWT subject by way of {@code UserRequestContext}, never from a request
 * body, so a client that supplies one is ignored rather than obeyed. What is left writable —
 * {@code name} and {@code sortOrder} — is exactly what {@code PUT /chatgroups/{chatGroupId}}
 * updates, which is why that endpoint takes this entity rather than a request record of its own.
 */
@Entity
public class ChatGroup {
    @Id
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID userId;

    private String name;

    /**
     * Where this section sits in the caller's list of sections when it has been placed by hand, or
     * null when it has not — which is every group until a client places one. Null is what makes the
     * ordering fall back to name, so a group nobody has arranged is still where it was before
     * ordering existed, and a newly created one lands among the unplaced groups rather than at the
     * top of the arrangement.
     * <p>
     * A rank, not an index. Unlike the two positions on a chat — which the server renumbers densely
     * on every move — this number is stored exactly as the client sends it, because the update that
     * writes it is a pure update of one group and touches no other row. Nothing may read it as an
     * offset into the listing: gaps and duplicates are both legal, and the listing breaks a tie by
     * name and then by id so two groups sharing a rank never swap places between requests.
     */
    private Integer sortOrder;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private ZonedDateTime timestamp;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public ZonedDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(ZonedDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
