package com.solesonic.model.interest;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One run that delivered news, and the chat it created. The id is the run's, assigned before the
 * run writes anything, and doubles as the push dedupe key.
 */
@Entity
public class InterestUpdate {
    @Id
    private UUID id;

    private UUID userId;

    private UUID agentId;

    private String label;

    private UUID chatId;

    private int itemCount;

    private ZonedDateTime created;

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

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public UUID getChatId() {
        return chatId;
    }

    public void setChatId(UUID chatId) {
        this.chatId = chatId;
    }

    public int getItemCount() {
        return itemCount;
    }

    public void setItemCount(int itemCount) {
        this.itemCount = itemCount;
    }

    public ZonedDateTime getCreated() {
        return created;
    }

    public void setCreated(ZonedDateTime created) {
        this.created = created;
    }
}
