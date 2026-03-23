package com.solesonic.model.chat.history;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;
import org.springframework.ai.chat.messages.MessageType;

import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;

@Entity
public class ChatMessage {
    @Id
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID chatId;

    private ZonedDateTime timestamp;

    @Enumerated(EnumType.STRING)
    private MessageType messageType;

    @Column(columnDefinition = "TEXT")
    private String message;

    private String model;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Set<String> commands;

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

    public MessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(MessageType messageType) {
        this.messageType = messageType;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String userMessage) {
        this.message = userMessage;
    }

    @SuppressWarnings("unused")
    public UUID getChatId() {
        return chatId;
    }

    public void setChatId(UUID chatId) {
        this.chatId = chatId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Set<String> getCommands() {
        return commands;
    }

    public void setCommands(Set<String> commands) {
        this.commands = commands;
    }
}
