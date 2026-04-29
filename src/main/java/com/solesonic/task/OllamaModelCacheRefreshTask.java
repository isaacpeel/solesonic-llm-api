package com.solesonic.task;

import com.solesonic.service.ollama.OllamaModelCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "solesonic.llm.ollama.cache.refresh.enabled", havingValue = "true")
public class OllamaModelCacheRefreshTask {
    private static final Logger log = LoggerFactory.getLogger(OllamaModelCacheRefreshTask.class);

    private final OllamaApi ollamaApi;
    private final OllamaModelCacheService ollamaModelCacheService;
    private final JsonMapper jsonMapper;

    public OllamaModelCacheRefreshTask(OllamaApi ollamaApi,
                                       OllamaModelCacheService ollamaModelCacheService,
                                       JsonMapper jsonMapper) {
        this.ollamaApi = ollamaApi;
        this.ollamaModelCacheService = ollamaModelCacheService;
        this.jsonMapper = jsonMapper;
    }

    @Scheduled(initialDelay = 5, fixedRateString = "${solesonic.llm.ollama.cache.refresh-seconds:60}", timeUnit = TimeUnit.SECONDS)
    public void refresh() {
        log.info("Refreshing Ollama model cache");
        try {
            OllamaApi.ListModelResponse listModelResponse = ollamaApi.listModels();

            for (OllamaApi.Model nativeModel : listModelResponse.models()) {
                String modelName = nativeModel.model();
                try {
                    String detailsJson = jsonMapper.writeValueAsString(nativeModel);
                    Map<String, Object> details = jsonMapper.readerFor(Map.class).readValue(detailsJson);
                    ollamaModelCacheService.putModelDetails(modelName, details);

                    OllamaApi.ShowModelResponse showModelResponse = ollamaApi.showModel(new OllamaApi.ShowModelRequest(modelName));
                    String showJson = jsonMapper.writeValueAsString(showModelResponse);
                    Map<String, Object> show = jsonMapper.readerFor(Map.class).readValue(showJson);
                    ollamaModelCacheService.putShowModel(modelName, show);

                    log.debug("Cached model: {}", modelName);
                } catch (Exception exception) {
                    log.warn("Failed to refresh cache for model {}: {}", modelName, exception.getMessage());
                }
            }
            log.info("Ollama model cache refresh complete — {} models cached", listModelResponse.models().size());
        } catch (Exception exception) {
            log.warn("Failed to refresh Ollama model cache: {}", exception.getMessage());
        }
    }
}
