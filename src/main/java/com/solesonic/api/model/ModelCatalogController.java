package com.solesonic.api.model;

import com.solesonic.model.llm.LlmModel;
import com.solesonic.service.model.ModelCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/models")
public class ModelCatalogController {
    private static final Logger log = LoggerFactory.getLogger(ModelCatalogController.class);

    private final ModelCatalogService modelCatalogService;

    public ModelCatalogController(ModelCatalogService modelCatalogService) {
        this.modelCatalogService = modelCatalogService;
    }

    @GetMapping
    @PreAuthorize("hasRole('model-admin')")
    public ResponseEntity<List<LlmModel>> models() {
        log.info("Getting all models");
        List<LlmModel> models = modelCatalogService.models();
        return ResponseEntity.ok(models);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('model-admin')")
    public ResponseEntity<LlmModel> model(@PathVariable UUID id) {
        LlmModel model = modelCatalogService.get(id);
        return ResponseEntity.ok(model);
    }

    @PostMapping
    @PreAuthorize("hasRole('model-admin')")
    public ResponseEntity<LlmModel> save(@RequestBody LlmModel model) {
        model = modelCatalogService.save(model);
        return ResponseEntity.ok(model);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('model-admin')")
    public ResponseEntity<LlmModel> update(@PathVariable UUID id, @RequestBody LlmModel model) {
        return ResponseEntity.ok(modelCatalogService.update(id, model));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('model-admin')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        modelCatalogService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
