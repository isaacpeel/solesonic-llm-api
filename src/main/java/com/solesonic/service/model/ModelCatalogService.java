package com.solesonic.service.model;

import com.solesonic.exception.ChatException;
import com.solesonic.model.llm.LlmModel;
import com.solesonic.repository.llm.LlmModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The model catalog: a static, database-backed list of the models a user may choose between in
 * their preferences.
 * <p>
 * It is a catalog and nothing more: nothing queries a model server, because a
 * {@code llama-server}-style process serves one fixed model for its whole lifetime, making "what is
 * installed" a deployment fact rather than a live query.
 */
@Service
public class ModelCatalogService {
    private static final Logger log = LoggerFactory.getLogger(ModelCatalogService.class);

    private final LlmModelRepository modelRepository;

    public ModelCatalogService(LlmModelRepository modelRepository) {
        this.modelRepository = modelRepository;
    }

    public List<LlmModel> models() {
        log.info("Getting models");
        List<LlmModel> models = modelRepository.findAll();

        log.info("Found {} models.", models.size());
        return models;
    }

    public LlmModel get(UUID id) {
        log.info("Getting model with id {}", id);

        return modelRepository.findById(id)
                .orElseThrow(() -> new ChatException("MODEL NOT FOUND"));
    }

    public LlmModel save(LlmModel model) {
        model.setCreated(ZonedDateTime.now());
        model.setUpdated(ZonedDateTime.now());

        return modelRepository.save(model);
    }

    public LlmModel update(UUID id, LlmModel model) {
        model.setId(id);
        model.setUpdated(ZonedDateTime.now());

        return modelRepository.save(model);
    }

    public void delete(UUID id) {
        modelRepository.deleteById(id);
    }
}
