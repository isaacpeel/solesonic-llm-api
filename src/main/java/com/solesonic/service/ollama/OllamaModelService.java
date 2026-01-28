package com.solesonic.service.ollama;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Service
@SuppressWarnings("unused")
public class OllamaModelService {

    private final JsonMapper jsonMapper;

    public OllamaModelService(JsonMapper jsonMapper, JsonMapper jsonMapper1) {
        this.jsonMapper = jsonMapper1;
    }



    public JsonNode readPath(Map<String, Object> jsonData, String... path) {
        String json = jsonMapper.writeValueAsString(jsonData);
        JsonNode jsonNode = jsonMapper.readTree(json);

        for (String pathKey : path) {
            if (jsonNode == null || jsonNode.isMissingNode() || jsonNode.isNull()) {
                return null;
            }

            if (jsonNode.isArray()) {
                for (JsonNode element : jsonNode) {
                    if (element.asString().equals(pathKey)) {
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

    @SuppressWarnings("unused")
    public boolean hasNode(Map<String, Object> jsonData, String... path) {
        JsonNode foundNode = readPath(jsonData, path);

        return foundNode != null;
    }

    @SuppressWarnings("unused")
    public String readText(Map<String, Object> jsonData, String... path) {
        JsonNode jsonNode = readPath(jsonData, path);

        return jsonNode != null && jsonNode.isString() ? jsonNode.asString() : null;
    }

    @SuppressWarnings("unused")
    public <T> T toTreeValue(Object pojo, Class<T> clazz) {
        JsonNode node = jsonMapper.valueToTree(pojo);

        return jsonMapper.convertValue(node, clazz);
    }
}
