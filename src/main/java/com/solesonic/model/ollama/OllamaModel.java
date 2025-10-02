package com.solesonic.model.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.ai.ollama.api.OllamaApi;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
public class OllamaModel {
    @Id
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true)
    private String name;

    private boolean censored;

    @Transient
    private Map<String, Object> ollamaModel;

    @Transient
    private Map<String, Object> ollamaShow;

    private ZonedDateTime created;

    private ZonedDateTime updated;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @SuppressWarnings("unused")
    public boolean isCensored() {
        return censored;
    }

    public void setCensored(boolean censored) {
        this.censored = censored;
    }

    public ZonedDateTime getUpdated() {
        return updated;
    }

    public void setUpdated(ZonedDateTime updated) {
        this.updated = updated;
    }

    public ZonedDateTime getCreated() {
        return created;
    }

    public void setCreated(ZonedDateTime created) {
        this.created = created;
    }

    public Map<String, Object> getOllamaModel() {
        return ollamaModel;
    }

    public void setOllamaModel(Map<String, Object> ollamaModel) {
        this.ollamaModel = ollamaModel;
    }

    public Map<String, Object> getOllamaShow() {
        return ollamaShow;
    }

    public void setOllamaShow(Map<String, Object> ollamaShow) {
        this.ollamaShow = ollamaShow;
    }
}
