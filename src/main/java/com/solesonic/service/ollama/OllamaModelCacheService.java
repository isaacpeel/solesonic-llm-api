package com.solesonic.service.ollama;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class OllamaModelCacheService {
    private static final Logger log = LoggerFactory.getLogger(OllamaModelCacheService.class);
    private static final String MODEL_DETAILS_KEY_PREFIX = "ollama:model-details:";
    private static final String SHOW_MODEL_KEY_PREFIX = "ollama:show-model:";

    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper jsonMapper;

    @Value("${solesonic.llm.ollama.cache.ttl-seconds:120}")
    private long cacheTtlSeconds;

    public OllamaModelCacheService(StringRedisTemplate stringRedisTemplate, JsonMapper jsonMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jsonMapper = jsonMapper;
    }

    public Optional<Map<String, Object>> getModelDetails(String modelName) {
        String json = stringRedisTemplate.opsForValue().get(MODEL_DETAILS_KEY_PREFIX + modelName);
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(jsonMapper.readerFor(Map.class).readValue(json));
    }

    public void putModelDetails(String modelName, Map<String, Object> details) {
        String json = jsonMapper.writeValueAsString(details);
        stringRedisTemplate.opsForValue().set(MODEL_DETAILS_KEY_PREFIX + modelName, json, Duration.ofSeconds(cacheTtlSeconds));
    }

    public Optional<Map<String, Object>> getShowModel(String modelName) {
        String json = stringRedisTemplate.opsForValue().get(SHOW_MODEL_KEY_PREFIX + modelName);
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(jsonMapper.readerFor(Map.class).readValue(json));
    }

    public void putShowModel(String modelName, Map<String, Object> show) {
        String json = jsonMapper.writeValueAsString(show);
        stringRedisTemplate.opsForValue().set(SHOW_MODEL_KEY_PREFIX + modelName, json, Duration.ofSeconds(cacheTtlSeconds));
    }

    public void evictModel(String modelName) {
        stringRedisTemplate.delete(MODEL_DETAILS_KEY_PREFIX + modelName);
        stringRedisTemplate.delete(SHOW_MODEL_KEY_PREFIX + modelName);
        log.debug("Evicted cache for model: {}", modelName);
    }

    public void evictAll() {
        Set<String> modelDetailsKeys = stringRedisTemplate.keys(MODEL_DETAILS_KEY_PREFIX + "*");
        Set<String> showModelKeys = stringRedisTemplate.keys(SHOW_MODEL_KEY_PREFIX + "*");
        if (modelDetailsKeys != null && !modelDetailsKeys.isEmpty()) {
            stringRedisTemplate.delete(modelDetailsKeys);
        }
        if (showModelKeys != null && !showModelKeys.isEmpty()) {
            stringRedisTemplate.delete(showModelKeys);
        }
        log.info("Evicted all Ollama model cache entries");
    }
}
