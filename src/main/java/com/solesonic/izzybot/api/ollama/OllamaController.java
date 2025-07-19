package com.solesonic.izzybot.api.ollama;

import com.solesonic.izzybot.model.ollama.OllamaModel;
import com.solesonic.izzybot.service.ollama.OllamaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/izzybot/ollama")
public class OllamaController {
    private final OllamaService ollamaService;

    public OllamaController(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    @GetMapping("/models")
    public ResponseEntity<List<OllamaModel>> models() {
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
        List<OllamaModel> models = ollamaService.installed();
        return ResponseEntity.ok(models);
    }
}
