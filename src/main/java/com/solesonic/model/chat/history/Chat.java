package com.solesonic.model.chat.history;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("unused")
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

    /**
     * Where this conversation sits in the user's whole list when it has been placed by hand, or
     * null when it has not — which is every chat until a client moves one. Null is what makes the
     * ordering fall back to {@code timestamp desc}, so a conversation nobody has arranged is still
     * where it was before manual ordering existed.
     * <p>
     * Read-only on the wire for the same reason {@link #chatGroupId} is: it is changed through
     * {@code /chats/{chatId}/order}, which resolves the owner from the bearer token, and never by
     * writing a number onto a chat.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer sortOrder;

    /**
     * The same position, within the chat's group. Kept apart from {@link #sortOrder} because the
     * two lists are ordered independently — a move in the sidebar must not reshuffle a group, and a
     * move inside a group must not reshuffle the sidebar.
     * <p>
     * Cleared whenever the chat changes group: a position in a group it is no longer in describes
     * nothing.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer groupSortOrder;

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

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Integer getGroupSortOrder() {
        return groupSortOrder;
    }

    public void setGroupSortOrder(Integer groupSortOrder) {
        this.groupSortOrder = groupSortOrder;
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
