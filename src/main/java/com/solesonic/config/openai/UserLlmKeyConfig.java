package com.solesonic.config.openai;

import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.service.llm.UserLlmKeyInterceptor;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts {@link UserLlmKeyInterceptor} on every OpenAI client. The autoconfigured chat and embedding
 * models collect every {@link OpenAiHttpClientBuilderCustomizer} bean themselves; the hand-built
 * models in this package and {@link OpenAiClientConfig} are handed the same list explicitly.
 */
@Configuration
public class UserLlmKeyConfig {

    @Bean
    public OpenAiHttpClientBuilderCustomizer userLlmKeyCustomizer(@Value("${spring.ai.openai.api-key}") String serviceApiKey) {
        UserLlmKeyInterceptor userLlmKeyInterceptor = new UserLlmKeyInterceptor(new LlmApiKey(serviceApiKey));

        return builder -> builder.interceptor(userLlmKeyInterceptor);
    }
}
