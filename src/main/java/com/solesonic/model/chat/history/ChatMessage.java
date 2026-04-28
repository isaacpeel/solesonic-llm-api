package com.solesonic.model.chat.history;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.ai.chat.messages.MessageType;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("unused")
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

    private UUID elicitationId;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> elicitationResponse;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> progressData;

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

    public UUID getElicitationId() {
        return elicitationId;
    }

    public void setElicitationId(UUID elicitationId) {
        this.elicitationId = elicitationId;
    }

    public Map<String, Object> getElicitationResponse() {
        return elicitationResponse;
    }

    public void setElicitationResponse(Map<String, Object> elicitationResponse) {
        this.elicitationResponse = elicitationResponse;
    }

    public Map<String, Object> getProgressData() {
        return progressData;
    }

    public void setProgressData(Map<String, Object> progressData) {
        this.progressData = progressData;
    }
}
