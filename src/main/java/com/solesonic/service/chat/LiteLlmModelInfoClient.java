package com.solesonic.service.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.solesonic.model.llm.LlmApiKey;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the custom display settings an admin puts on a LiteLLM model: {@code display_name} (the label a
 * picker shows) and {@code ui_hidden} (leave it out of a picker). Either may sit in {@code model_info} or
 * in {@code litellm_params} (the admin UI's "LiteLLM Params"); {@code model_info} wins when both are set.
 * LiteLLM returns them only from {@code /model/info}, never from the OpenAI-shaped {@code /models}.
 * <p>
 * Purely cosmetic, so it never fails a caller: when the endpoint cannot be read the answer is empty and
 * every model shows as its id, as it did before these settings existed.
 */
@Component
public class LiteLlmModelInfoClient {
    private static final Logger log = LoggerFactory.getLogger(LiteLlmModelInfoClient.class);

    static final String MODEL_INFO_PATH = "/model/info";

    private final RestClient restClient;

    /**
     * @param baseUrl the chat endpoint's base URL, which already ends in {@code /v1}; LiteLLM serves
     *                {@code /v1/model/info} beside {@code /v1/models}
     */
    public LiteLlmModelInfoClient(RestClient.Builder restClientBuilder,
                                  @Value("${spring.ai.openai.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.clone()
                .baseUrl(StringUtils.removeEnd(baseUrl, "/"))
                .build();
    }

    /**
     * Display settings by lower-cased model name, for the models the key can see. A model name with
     * several deployments takes the first non-blank {@code display_name}, and is hidden only when every
     * deployment says {@code ui_hidden: true}, the same rule LiteLLM applies to {@code discoverable}.
     */
    public Map<String, ModelDisplaySettings> displaySettings(LlmApiKey llmApiKey) {
        ModelInfoResponse modelInfoResponse;

        try {
            modelInfoResponse = restClient.get()
                    .uri(MODEL_INFO_PATH)
                    .header(HttpHeaders.AUTHORIZATION, llmApiKey.bearer())
                    .retrieve()
                    .body(ModelInfoResponse.class);
        } catch (RuntimeException exception) {
            log.warn("Could not read model display settings, showing model ids: {}", exception.getMessage());

            return Map.of();
        }

        if (modelInfoResponse == null || modelInfoResponse.data() == null) {
            return Map.of();
        }

        Map<String, ModelDisplaySettings> settingsByName = new HashMap<>();

        modelInfoResponse.data().stream()
                .filter(Objects::nonNull)
                .filter(deployment -> StringUtils.isNotBlank(deployment.modelName()))
                .forEach(deployment -> settingsByName.merge(
                        deployment.modelName().toLowerCase(Locale.ROOT),
                        ModelDisplaySettings.of(deployment.modelInfo(), deployment.litellmParams()),
                        ModelDisplaySettings::merge));

        return settingsByName;
    }

    /**
     * @param displayName null when none is set
     */
    public record ModelDisplaySettings(String displayName, boolean hidden) {

        static ModelDisplaySettings of(DisplayFields modelInfo, DisplayFields litellmParams) {
            DisplayFields preferred = modelInfo != null ? modelInfo : DisplayFields.NONE;
            DisplayFields fallback = litellmParams != null ? litellmParams : DisplayFields.NONE;

            String displayName = StringUtils.firstNonBlank(preferred.displayName(), fallback.displayName());
            Boolean uiHidden = preferred.uiHidden() != null ? preferred.uiHidden() : fallback.uiHidden();

            return new ModelDisplaySettings(StringUtils.trimToNull(displayName), Boolean.TRUE.equals(uiHidden));
        }

        ModelDisplaySettings merge(ModelDisplaySettings other) {
            String mergedDisplayName = displayName != null ? displayName : other.displayName;

            return new ModelDisplaySettings(mergedDisplayName, hidden && other.hidden);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelInfoResponse(List<Deployment> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Deployment(@JsonProperty("model_name") String modelName,
                      @JsonProperty("model_info") DisplayFields modelInfo,
                      @JsonProperty("litellm_params") DisplayFields litellmParams) {
    }

    /**
     * The two keys as they appear in either {@code model_info} or {@code litellm_params}. {@code ui_hidden}
     * is read as a Boolean, so a value of another type fails the parse into the warning path instead of
     * hiding a model by accident.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DisplayFields(@JsonProperty("display_name") String displayName,
                         @JsonProperty("ui_hidden") Boolean uiHidden) {

        static final DisplayFields NONE = new DisplayFields(null, null);
    }
}
