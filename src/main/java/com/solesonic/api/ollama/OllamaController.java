package com.solesonic.api.ollama;

import com.solesonic.model.ollama.OllamaModel;
import com.solesonic.service.ollama.OllamaModelCacheService;
import com.solesonic.service.ollama.OllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/ollama")
public class OllamaController {
    private static final Logger log = LoggerFactory.getLogger(OllamaController.class);
    private final OllamaService ollamaService;
    private final OllamaModelCacheService ollamaModelCacheService;

    public OllamaController(OllamaService ollamaService, OllamaModelCacheService ollamaModelCacheService) {
        this.ollamaService = ollamaService;
        this.ollamaModelCacheService = ollamaModelCacheService;
    }

    @GetMapping("/models")
    @PreAuthorize("hasRole('model-admin')")
    public ResponseEntity<List<OllamaModel>> models(@RequestParam(defaultValue = "false") boolean refresh) {
        log.info("Getting all models (refresh={})", refresh);
        if (refresh) {
            ollamaModelCacheService.evictAll();
        }
        List<OllamaModel> models = ollamaService.models();
        return ResponseEntity.ok(models);
    }

    @GetMapping("/models/{id}")
    @PreAuthorize("hasRole('model-admin')")
    public ResponseEntity<OllamaModel> model(@PathVariable UUID id) {
        OllamaModel model = ollamaService.get(id);
        return ResponseEntity.ok(model);
    }

    @PostMapping("/models")
    @PreAuthorize("hasRole('model-admin')")
    public ResponseEntity<OllamaModel> save(@RequestBody OllamaModel model) {
        model = ollamaService.save(model);
        return ResponseEntity.ok(model);
    }

    @PutMapping("/models/{id}")
    @PreAuthorize("hasRole('model-admin')")
    public ResponseEntity<OllamaModel> update(@PathVariable UUID id, @RequestBody OllamaModel model) {
        return ResponseEntity.ok(ollamaService.update(id, model));
    }

    @DeleteMapping("/models/{id}")
    @PreAuthorize("hasRole('model-admin')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ollamaService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/installed")
    @PreAuthorize("hasRole('model-admin')")
    public ResponseEntity<List<OllamaModel>> installed() {
        log.info("Getting Installed Ollama Models");
        List<OllamaModel> models = ollamaService.installed();
        return ResponseEntity.ok(models);
    }

    @PostMapping("/models/refresh")
    @PreAuthorize("hasRole('model-admin')")
    public ResponseEntity<Void> refresh() {
        log.info("Refreshing Ollama model cache");
        ollamaService.refreshCache();
        return ResponseEntity.noContent().build();
    }
}
