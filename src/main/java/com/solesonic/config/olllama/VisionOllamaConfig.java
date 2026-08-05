package com.solesonic.config.olllama;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.springframework.ai.ollama.management.PullModelStrategy.WHEN_MISSING;

/**
 * The vision model used to describe image attachments. Configured separately from the chat model —
 * its own host, model, and timeout — so it can run on different hardware, exactly as
 * {@link EtlOllamaConfig} does for enrichment.
 */
@Configuration
public class VisionOllamaConfig {
    public static final String VISION_CHAT_MODEL = "VISION_CHAT_MODEL";

    /**
     * {@code defaultCandidate = false} is required, not stylistic: without it
     * {@link ChatConfig#defaultChatClient} sees two {@link OllamaChatModel} candidates and the
     * context fails to start. Inject this bean by {@link Qualifier} only.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(VISION_CHAT_MODEL)
    public OllamaChatModel visionChatModel(@Value("${solesonic.llm.vision.ollama.host}") String ollamaBaseUrl,
                                           @Value("${solesonic.llm.vision.model}") String visionModel,
                                           @Value("${solesonic.llm.vision.ollama.read-timeout}") Duration readTimeout,
                                           @Value("${solesonic.llm.vision.ollama.keep-alive}") String keepAlive) {
        OllamaApi ollamaApi = visionOllamaApi(ollamaBaseUrl, readTimeout);

        //The generation budget has to hold the model's reasoning as well as the description.
        //qwen3-vl's default tag is the thinking variant, and it ignores think=false — the reasoning
        //comes back in the response's thinking field and the description in content, so a budget
        //that runs out mid-reasoning yields an empty content and an undescribed image rather than a
        //truncated one. A text-dense photo (a rack of cartridges, transcribed label by label) spent
        //5.5k tokens reasoning before writing a 900-character description; at the old 384 it never
        //reached the description at all, and at 16k context an unbounded run filled the window
        //first. 32k context leaves room for the image, the reasoning, and the answer.
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(visionModel)
                .numCtx(32768)
                .numBatch(512)
                .keepAlive(keepAlive)
                .temperature(0.2)
                .numPredict(16384)
                .disableThinking()
                .build();

        ModelManagementOptions modelManagementOptions = ModelManagementOptions.builder()
                .pullModelStrategy(WHEN_MISSING)
                .build();

        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(options)
                .modelManagementOptions(modelManagementOptions)
                .build();
    }

    /**
     * Extended read timeout, for the same reason {@link EtlOllamaConfig} needs one: a cold
     * vision-model load in Ollama routinely outlives the default read timeout and surfaces as a
     * {@code ReadTimeoutException} on the first request rather than waiting for the load to finish.
     */
    private OllamaApi visionOllamaApi(String ollamaBaseUrl, Duration readTimeout) {
        HttpClientSettings httpClientSettings = HttpClientSettings.defaults()
                .withReadTimeout(readTimeout);

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(httpClientSettings));

        return OllamaApi.builder()
                .baseUrl(ollamaBaseUrl)
                .restClientBuilder(restClientBuilder)
                .build();
    }
}
