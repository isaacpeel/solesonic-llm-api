package com.solesonic.izzybot.model.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import org.springframework.ai.ollama.api.OllamaApi;

import java.time.ZonedDateTime;
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
    private boolean embedding;
    private boolean tools;
    private boolean vision;

    @Transient
    private String model;

    @Transient
    private Long size;

    @Transient
    private OllamaApi.Model.Details details;

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

    public boolean isCensored() {
        return censored;
    }

    public void setCensored(boolean censored) {
        this.censored = censored;
    }

    public boolean isEmbedding() {
        return embedding;
    }

    public void setEmbedding(boolean embedding) {
        this.embedding = embedding;
    }

    public boolean isTools() {
        return tools;
    }

    public void setTools(boolean tools) {
        this.tools = tools;
    }

    public boolean isVision() {
        return vision;
    }

    public void setVision(boolean vision) {
        this.vision = vision;
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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public OllamaApi.Model.Details getDetails() {
        return details;
    }

    public void setDetails(OllamaApi.Model.Details details) {
        this.details = details;
    }
}
