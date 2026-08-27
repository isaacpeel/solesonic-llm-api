package com.solesonic.model.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One entry in the model catalog — the set of models a user may pick between in their preferences.
 * <p>
 * {@code @Table} is pinned explicitly because the Java name no longer matches the table name: the
 * type was renamed away from Ollama, the production table was not. Renaming the table itself is a
 * data migration for a cosmetic win, so it is deliberately left alone.
 */
@Entity
@Table(name = "ollama_model")
public class LlmModel {
    @Id
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true)
    private String name;

    private boolean censored;

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
}
