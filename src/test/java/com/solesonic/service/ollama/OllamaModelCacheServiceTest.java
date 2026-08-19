package com.solesonic.service.ollama;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class OllamaModelCacheServiceTest {
    private static final String MODEL_DETAILS_KEY = "ollama:model-details:llama3";
    private static final String SHOW_MODEL_KEY = "ollama:show-model:llama3";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private OllamaModelCacheService ollamaModelCacheService;

    @BeforeEach
    void setUp() {
        lenient().doReturn(valueOperations).when(stringRedisTemplate).opsForValue();
        ollamaModelCacheService = new OllamaModelCacheService(stringRedisTemplate, new JsonMapper());
    }

    @Test
    void testPutModelDetailsWritesWithoutTtl() {
        ollamaModelCacheService.putModelDetails("llama3", Map.of("model", "llama3"));

        verify(valueOperations).set(MODEL_DETAILS_KEY, "{\"model\":\"llama3\"}");
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void testPutShowModelWritesWithoutTtl() {
        ollamaModelCacheService.putShowModel("llama3", Map.of("modelfile", "FROM llama3"));

        verify(valueOperations).set(SHOW_MODEL_KEY, "{\"modelfile\":\"FROM llama3\"}");
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void testGetModelDetails() {
        when(valueOperations.get(MODEL_DETAILS_KEY)).thenReturn("{\"model\":\"llama3\"}");

        Optional<Map<String, Object>> modelDetails = ollamaModelCacheService.getModelDetails("llama3");

        assertThat(modelDetails).isPresent();
        assertThat(modelDetails.orElseThrow()).containsEntry("model", "llama3");
    }

    @Test
    void testGetModelDetailsMiss() {
        when(valueOperations.get(MODEL_DETAILS_KEY)).thenReturn(null);

        assertThat(ollamaModelCacheService.getModelDetails("llama3")).isEmpty();
    }

    @Test
    void testGetShowModel() {
        when(valueOperations.get(SHOW_MODEL_KEY)).thenReturn("{\"modelfile\":\"FROM llama3\"}");

        Optional<Map<String, Object>> showModel = ollamaModelCacheService.getShowModel("llama3");

        assertThat(showModel).isPresent();
        assertThat(showModel.orElseThrow()).containsEntry("modelfile", "FROM llama3");
    }

    @Test
    void testGetShowModelMiss() {
        when(valueOperations.get(SHOW_MODEL_KEY)).thenReturn(null);

        assertThat(ollamaModelCacheService.getShowModel("llama3")).isEmpty();
    }

    @Test
    void testEvictModel() {
        ollamaModelCacheService.evictModel("llama3");

        verify(stringRedisTemplate).delete(MODEL_DETAILS_KEY);
        verify(stringRedisTemplate).delete(SHOW_MODEL_KEY);
    }

    @Test
    void testEvictAll() {
        Set<String> modelDetailsKeys = Set.of(MODEL_DETAILS_KEY);
        Set<String> showModelKeys = Set.of(SHOW_MODEL_KEY);

        when(stringRedisTemplate.keys("ollama:model-details:*")).thenReturn(modelDetailsKeys);
        when(stringRedisTemplate.keys("ollama:show-model:*")).thenReturn(showModelKeys);

        ollamaModelCacheService.evictAll();

        verify(stringRedisTemplate).delete(modelDetailsKeys);
        verify(stringRedisTemplate).delete(showModelKeys);
    }

    @Test
    void testEvictAllWithNoKeys() {
        when(stringRedisTemplate.keys("ollama:model-details:*")).thenReturn(Set.of());
        when(stringRedisTemplate.keys("ollama:show-model:*")).thenReturn(Set.of());

        ollamaModelCacheService.evictAll();

        verify(stringRedisTemplate, never()).delete(anyCollection());
    }
}
