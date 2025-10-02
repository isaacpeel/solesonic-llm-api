package com.solesonic.service.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class OllamaModelService {

    private final ObjectMapper objectMapper;

    public OllamaModelService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode readPath(Map<String, Object> jsonData, String... path) {
        JsonNode jsonNode = objectMapper.valueToTree(jsonData);

        for (String pathKey : path) {
            if (jsonNode == null || jsonNode.isMissingNode() || jsonNode.isNull()) {
                return null;
            }

            if (jsonNode.isArray()) {
                for (JsonNode element : jsonNode) {
                    if (element.asText().equals(pathKey)) {
                        return element;
                    }
                }
            } else {
                jsonNode = jsonNode.get(pathKey);
            }
        }

        if (jsonNode == null || jsonNode.isMissingNode() || jsonNode.isNull()) {
            return null;
        }

        return jsonNode;
    }

    public boolean hasNode(Map<String, Object> jsonData, String... path) {
        JsonNode foundNode = readPath(jsonData, path);

        return foundNode != null;
    }

    @SuppressWarnings("unused")
    public String readText(Map<String, Object> jsonData, String... path) {
        JsonNode jsonNode = readPath(jsonData, path);

        return jsonNode != null && jsonNode.isTextual() ? jsonNode.asText() : null;
    }

    @SuppressWarnings("unused")
    public <T> T toTreeValue(Object pojo, Class<T> clazz) {
        JsonNode node = objectMapper.valueToTree(pojo);

        return objectMapper.convertValue(node, clazz);
    }
}
