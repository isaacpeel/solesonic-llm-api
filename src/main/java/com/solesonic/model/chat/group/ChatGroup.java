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
 * body, so a client that supplies one is ignored rather than obeyed.
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

    public ZonedDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(ZonedDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
