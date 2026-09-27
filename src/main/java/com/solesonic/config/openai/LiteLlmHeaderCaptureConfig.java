package com.solesonic.config.openai;

import com.solesonic.service.litellm.LiteLlmHeaderInterceptor;
import com.solesonic.service.litellm.LiteLlmHeaderRegistry;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Puts {@link LiteLlmHeaderInterceptor} on the OkHttp client behind every OpenAI model.
 * <p>
 * {@code OpenAiHttpClientBuilderCustomizer} is Spring AI's own extension point for exactly this —
 * {@code OpenAiChatAutoConfiguration} collects every bean of the type through an
 * {@code ObjectProvider} and applies it before the client is built. Going through it is what lets
 * the chat model stay autoconfigured; supplying a hand-built {@code OpenAIClient} instead would mean
 * hand-building the chat model too, which {@code ChatConfig} deliberately does not do.
 */
@Configuration
public class LiteLlmHeaderCaptureConfig {

    /**
     * {@link LiteLlmHeaderRegistry} expires entries, so it needs a clock it can be tested against
     * without sleeping. Nothing else in the application injects one.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public OpenAiHttpClientBuilderCustomizer liteLlmHeaderCustomizer(LiteLlmHeaderRegistry liteLlmHeaderRegistry,
                                                                    JsonMapper jsonMapper) {

        return builder -> builder.interceptor(new LiteLlmHeaderInterceptor(liteLlmHeaderRegistry, jsonMapper));
    }
}
