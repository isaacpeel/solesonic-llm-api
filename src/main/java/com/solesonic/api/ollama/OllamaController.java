package com.solesonic.api.ollama;

import com.solesonic.model.ollama.OllamaModel;
import com.solesonic.service.ollama.OllamaModelCacheService;
import com.solesonic.service.ollama.OllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<List<OllamaModel>> models(@RequestParam(defaultValue = "false") boolean refresh) {
        log.info("Getting all models (refresh={})", refresh);
        if (refresh) {
            ollamaModelCacheService.evictAll();
        }
        List<OllamaModel> models = ollamaService.models();
        return ResponseEntity.ok(models);
    }

    @GetMapping("/models/{id}")
    public ResponseEntity<OllamaModel> model(@PathVariable UUID id) {
        OllamaModel model = ollamaService.get(id);
        return ResponseEntity.ok(model);
    }

    @PostMapping("/models")
    public ResponseEntity<OllamaModel> save(@RequestBody OllamaModel model) {
        model = ollamaService.save(model);
        return ResponseEntity.ok(model);
    }

    @PutMapping("/models/{id}")
    public ResponseEntity<OllamaModel> update(@PathVariable UUID id, @RequestBody OllamaModel model) {
        model = ollamaService.update(id, model);
        return ResponseEntity.ok(model);
    }

    @GetMapping("/installed")
    public ResponseEntity<List<OllamaModel>> installed() {
        log.info("Getting Installed Ollama Models");
        List<OllamaModel> models = ollamaService.installed();
        return ResponseEntity.ok(models);
    }
}
