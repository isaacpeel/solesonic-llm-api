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
    public static final String CAPABILITIES = "capabilities";
    @Id
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true)
    private String name;

    private boolean censored;

    @Formula("coalesce(((ollama_show -> 'capabilities') @> '[\"embedding\"]'::jsonb), false)")
    private boolean embedding;

    @Formula("coalesce(((ollama_show -> 'capabilities') @> '[\"tools\"]'::jsonb), false)")
    private boolean tools;

    @Formula("coalesce(((ollama_show -> 'capabilities') @> '[\"vision\"]'::jsonb), false)")
    private boolean vision;

    @Transient
    private String model;

    @Formula("coalesce(((ollama_show -> 'capabilities') @> '[\"thinking\"]'::jsonb), false)")
    private boolean thinking;

    @Formula("coalesce(((ollama_model ->> 'size')::bigint), 0::bigint)")
    private Long size;

    @Transient
    private OllamaApi.Model.Details details;

    @Column(name = "ollama_model")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> ollamaModel;

    @Column(name = "ollama_show")
    @JdbcTypeCode(SqlTypes.JSON)
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
        if (ollamaModel != null && ollamaModel.get("model") instanceof String m) {
            return m;
        }

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

    public boolean isThinking() {
        return thinking;
    }

    public void setThinking(boolean thinking) {
        this.thinking = thinking;
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

    private boolean detectEmbeddingFromModelJson() {
        if (ollamaModel == null) {
            return false;
        }

        Object modelField = ollamaModel.get("model");

        if (modelField instanceof String ms && ms.toLowerCase().contains("embed")) {
            return true;
        }

        Object nameField = ollamaModel.get("name");

        if (nameField instanceof String ns && ns.toLowerCase().contains("embed")) {
            return true;
        }

        Object detailsField = ollamaModel.get("details");

        if (detailsField instanceof Map<?, ?> detailsMap) {
            Object family = detailsMap.get("family");

            if (family instanceof String fam && fam.toLowerCase().contains("embed")) {
                return true;
            }

            Object families = detailsMap.get("families");

            if (families instanceof List<?> familyList) {
                for (Object f : familyList) {
                    if (f instanceof String fs && fs.toLowerCase().contains("embed")) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
