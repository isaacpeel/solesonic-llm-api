package com.solesonic.service.ollama;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.exception.ChatException;
import com.solesonic.model.ollama.OllamaModel;
import com.solesonic.repository.ollama.OllamaModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OllamaService {
    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);
    public static final String EMBEDDING = "embedding";
    public static final String TOOLS = "tools";
    public static final String VISION = "vision";
    public static final String THINKING = "thinking";
    public static final String CAPABILITIES = "capabilities";

    private final OllamaApi ollamaApi;
    private final OllamaModelRepository modelRepository;
    private final ObjectMapper objectMapper;

    public OllamaService(OllamaApi ollamaApi,
                         OllamaModelRepository modelRepository,
                         ObjectMapper objectMapper) {
        this.ollamaApi = ollamaApi;
        this.modelRepository = modelRepository;
        this.objectMapper = objectMapper;
    }

    public List<OllamaModel> models() {
        List<OllamaModel> ollamaModels = modelRepository.findAll();

        List<OllamaModel> enriched = new ArrayList<>();

        for(OllamaModel ollamaModel : ollamaModels) {
            enriched.add(nativeModel(ollamaModel));
        }

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
        return nativeModel(model);
    }

    public OllamaModel update(UUID id, OllamaModel model) {
        model.setId(id);
        model.setUpdated(ZonedDateTime.now());

        model = modelRepository.save(model);
        return nativeModel(model);
    }

    public List<OllamaModel> installed() {
        OllamaApi.ListModelResponse listModelResponse = ollamaApi.listModels();
        List<OllamaModel> ollamaModels = new ArrayList<>();

        for (OllamaApi.Model model : listModelResponse.models()) {
            String modelName = model.name();

            OllamaModel ollamaModel = nativeModel(modelName);

            ollamaModels.add(ollamaModel);
        }

        return ollamaModels;
    }

    private OllamaModel nativeModel(String modelName) {
        OllamaModel ollamaModel = new OllamaModel();
        ollamaModel.setName(modelName);

        return nativeModel(ollamaModel);
    }

    private OllamaModel nativeModel(OllamaModel ollamaModel) {
        OllamaApi.ListModelResponse nativeOllamaModels = ollamaApi.listModels();

        if (nativeOllamaModels != null) {
            String modelName = ollamaModel.getName();
            OllamaApi.Model nativeModel = nativeOllamaModels.models().stream()
                    .filter(model -> model.name().equals(modelName))
                    .findFirst()
                    .orElseThrow(() -> new ChatException("OLLAMA MODEL NOT FOUND"));

            OllamaApi.ShowModelRequest showModelRequest = new OllamaApi.ShowModelRequest(modelName);
            OllamaApi.ShowModelResponse showModelResponse = ollamaApi.showModel(showModelRequest);

            ollamaModel.setName(modelName);

            Map<String, Object> ollamaShow = objectMapper.convertValue(showModelResponse, new TypeReference<>() {});
            Map<String, Object> ollamaDetails = objectMapper.convertValue(nativeModel, new TypeReference<>() {});

            ollamaModel.setOllamaShow(ollamaShow);
            ollamaModel.setOllamaModel(ollamaDetails);

            return ollamaModel;
        }

        throw new ChatException("OLLAMA MODEL NOT FOUND");
    }
}
