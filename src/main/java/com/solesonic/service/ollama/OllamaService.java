package com.solesonic.service.ollama;

import com.solesonic.exception.ChatException;
import com.solesonic.model.ollama.OllamaModel;
import com.solesonic.repository.ollama.OllamaModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OllamaService {
    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);
    @SuppressWarnings("unused")
    public static final String EMBEDDING = "embedding";
    @SuppressWarnings("unused")
    public static final String TOOLS = "tools";
    @SuppressWarnings("unused")
    public static final String VISION = "vision";
    @SuppressWarnings("unused")
    public static final String THINKING = "thinking";
    @SuppressWarnings("unused")
    public static final String CAPABILITIES = "capabilities";

    private final OllamaApi ollamaApi;
    private final OllamaModelRepository modelRepository;
    private final OllamaModelCacheService ollamaModelCacheService;
    private final JsonMapper jsonMapper;

    public OllamaService(OllamaApi ollamaApi,
                         OllamaModelRepository modelRepository,
                         OllamaModelCacheService ollamaModelCacheService,
                         JsonMapper jsonMapper) {
        this.ollamaApi = ollamaApi;
        this.modelRepository = modelRepository;
        this.ollamaModelCacheService = ollamaModelCacheService;
        this.jsonMapper = jsonMapper;
    }

    public List<OllamaModel> models() {
        log.info("Getting models");
        List<OllamaModel> ollamaModels = modelRepository.findAll();

        List<OllamaModel> enriched = new ArrayList<>();

        for (OllamaModel ollamaModel : ollamaModels) {
            enriched.add(nativeModel(ollamaModel));
        }

        log.info("Found {} and enriched models.", enriched.size());
        return enriched;
    }

    public OllamaModel get(UUID id) {
        log.info("Getting ollama model with id {}", id);

        OllamaModel ollamaModel = modelRepository.findById(id)
                .orElseThrow(() -> new ChatException("OLLAMA MODEL NOT FOUND"));

        ollamaModel = modelRepository.save(ollamaModel);
        return nativeModel(ollamaModel);
    }

    public OllamaModel save(OllamaModel model) {
        model.setCreated(ZonedDateTime.now());
        model.setUpdated(ZonedDateTime.now());

        model = modelRepository.save(model);
        ollamaModelCacheService.evictModel(model.getName());
        return nativeModel(model);
    }

    public OllamaModel update(UUID id, OllamaModel model) {
        model.setId(id);
        model.setUpdated(ZonedDateTime.now());

        model = modelRepository.save(model);
        ollamaModelCacheService.evictModel(model.getName());
        return nativeModel(model);
    }

    public List<OllamaModel> installed() {
        log.info("Getting installed models");
        OllamaApi.ListModelResponse listModelResponse = ollamaApi.listModels();
        List<OllamaModel> ollamaModels = new ArrayList<>();

        for (OllamaApi.Model model : listModelResponse.models()) {
            String modelName = model.model();

            OllamaModel ollamaModel = nativeModel(modelName);

            ollamaModels.add(ollamaModel);
        }

        log.info("Found {} installed models.", ollamaModels.size());
        return ollamaModels;
    }

    private OllamaModel nativeModel(String modelName) {
        OllamaModel ollamaModel = new OllamaModel();
        ollamaModel.setName(modelName);

        return nativeModel(ollamaModel);
    }

    private OllamaModel nativeModel(OllamaModel ollamaModel) {
        String modelName = ollamaModel.getName();

        Map<String, Object> ollamaDetails = ollamaModelCacheService.getModelDetails(modelName)
                .orElseGet(() -> fetchAndCacheModelDetails(modelName));

        Map<String, Object> ollamaShow = ollamaModelCacheService.getShowModel(modelName)
                .orElseGet(() -> fetchAndCacheShowModel(modelName));

        ollamaModel.setOllamaModel(ollamaDetails);
        ollamaModel.setOllamaShow(ollamaShow);
        return ollamaModel;
    }

    private Map<String, Object> fetchAndCacheModelDetails(String modelName) {
        log.debug("Cache miss for model details: {} — fetching from Ollama", modelName);
        OllamaApi.ListModelResponse listModelResponse = ollamaApi.listModels();

        OllamaApi.Model nativeModel = listModelResponse.models().stream()
                .filter(model -> model.model().equals(modelName))
                .findFirst()
                .orElseThrow(() -> new ChatException("OLLAMA MODEL NOT FOUND"));

        String detailsJson = jsonMapper.writeValueAsString(nativeModel);
        Map<String, Object> details = jsonMapper.readerFor(Map.class).readValue(detailsJson);
        ollamaModelCacheService.putModelDetails(modelName, details);
        return details;
    }

    private Map<String, Object> fetchAndCacheShowModel(String modelName) {
        log.debug("Cache miss for show model: {} — fetching from Ollama", modelName);
        OllamaApi.ShowModelResponse showModelResponse = ollamaApi.showModel(new OllamaApi.ShowModelRequest(modelName));

        String showJson = jsonMapper.writeValueAsString(showModelResponse);
        Map<String, Object> show = jsonMapper.readerFor(Map.class).readValue(showJson);
        ollamaModelCacheService.putShowModel(modelName, show);
        return show;
    }
}
