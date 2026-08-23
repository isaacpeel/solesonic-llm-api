package com.solesonic.model.chat.history;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
public class Chat {
    @Id
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID userId;

    private ZonedDateTime timestamp;

    private String name;

    /**
     * The group this conversation is filed under, or null when it is ungrouped — which is every
     * chat until a client files it. Read-only on the wire: membership is changed through
     * {@code /chatgroups/{chatGroupId}/chats/{chatId}}, which checks that the caller owns both the
     * group and the chat, and never by writing a group id onto a chat.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID chatGroupId;

    @Transient
    private List<ChatMessage> chatMessages;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Set<String> activeCommands;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public ZonedDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(ZonedDateTime timestamp) {
        this.timestamp = timestamp;
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

    public UUID getChatGroupId() {
        return chatGroupId;
    }

    public void setChatGroupId(UUID chatGroupId) {
        this.chatGroupId = chatGroupId;
    }

    public List<ChatMessage> getChatMessages() {
        return chatMessages;
    }

    public void setChatMessages(List<ChatMessage> chatMessages) {
        this.chatMessages = chatMessages;
    }

    public Set<String> getActiveCommands() {
        return activeCommands;
    }

    public void setActiveCommands(Set<String> activeCommands) {
        this.activeCommands = activeCommands;
    }
}
