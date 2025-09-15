package com.solesonic.service.ollama;

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

    private final OllamaApi ollamaApi;
    private final OllamaModelRepository modelRepository;

    public OllamaService(OllamaApi ollamaApi, OllamaModelRepository modelRepository) {
        this.ollamaApi = ollamaApi;
        this.modelRepository = modelRepository;
    }

    public List<OllamaModel> models() {
        List<OllamaModel> ollamaModels = modelRepository.findAll();

        OllamaApi.ListModelResponse nativeOllamaModels = ollamaApi.listModels();

        assert nativeOllamaModels != null;



        Map<String, OllamaApi.Model> nativeModelMap = nativeOllamaModels.models()
                .stream()
                .collect(Collectors.toMap(OllamaApi.Model::name, model -> model));

        return ollamaModels.stream()
                .filter(ollamaModel -> nativeModelMap.containsKey(ollamaModel.getName()))
                .peek(ollamaModel -> {
                    OllamaApi.Model nativeModel = nativeModelMap.get(ollamaModel.getName());
                    ollamaModel.setDetails(nativeModel.details());
                    ollamaModel.setModel(nativeModel.model());
                    ollamaModel.setSize(nativeModel.size());

                    String name = ollamaModel.getName();

                    OllamaApi.ShowModelRequest showModelRequest = new OllamaApi.ShowModelRequest(name);
                    OllamaApi.ShowModelResponse showModelResponse = ollamaApi.showModel(showModelRequest);

                    List<String> capabilities = showModelResponse.capabilities();

                    for(String capability : capabilities) {
                        switch (capability) {
                            case "tools" -> ollamaModel.setTools(true);
                            case "vision" -> ollamaModel.setVision(true);
                            case "thinking" -> ollamaModel.setThinking(true);
                            case "embedding" -> ollamaModel.setEmbedding(true);
                        }
                    }
                })
                .collect(Collectors.toList());
    }

    public OllamaModel get(UUID id) {
        OllamaModel ollamaModel = modelRepository.findById(id)
                .orElseThrow(()->new ChatException("OLLAMA MODEL NOT FOUND"));

        ollamaModel =  modelRepository.save(ollamaModel);
        return nativeModel(ollamaModel);
    }

    public OllamaModel save(OllamaModel model) {
        model.setCreated(ZonedDateTime.now());
        model.setUpdated(ZonedDateTime.now());

        model =  modelRepository.save(model);
        return nativeModel(model);
    }

    public OllamaModel update(UUID id, OllamaModel model) {
        model.setId(id);
        model.setUpdated(ZonedDateTime.now());

        model =  modelRepository.save(model);
        return nativeModel(model);
    }

    public List<OllamaModel> installed() {
        OllamaApi.ListModelResponse listModelResponse = ollamaApi.listModels();
        List<OllamaModel> ollamaModels = new ArrayList<>();

        for( OllamaApi.Model model : listModelResponse.models() ) {
            OllamaModel ollamaModel = new OllamaModel();
            ollamaModel.setName(model.name());
            ollamaModel.setSize(model.size());
            ollamaModel.setModel(model.model());
            ollamaModel.setDetails(model.details());

            ollamaModels.add(ollamaModel);
        }

        return ollamaModels;
    }

    private OllamaModel nativeModel(OllamaModel ollamaModel) {
        OllamaApi.ListModelResponse nativeOllamaModels = ollamaApi.listModels();
        if (nativeOllamaModels != null) {
            OllamaApi.Model nativeModel = nativeOllamaModels.models().stream()
                    .filter(model -> model.name().equals(ollamaModel.getName()))
                    .findFirst()
                    .orElseThrow(()->new ChatException("OLLAMA MODEL NOT FOUND"));

            ollamaModel.setDetails(nativeModel.details());
            ollamaModel.setModel(nativeModel.model());
            ollamaModel.setSize(nativeModel.size());

            return ollamaModel;
        }

        throw new ChatException("OLLAMA MODEL NOT FOUND");
    }
}
