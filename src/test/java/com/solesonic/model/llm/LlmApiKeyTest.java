package com.solesonic.model.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmApiKeyTest {

    private static final String KEY = "sk-user-key-00001234";

    /**
     * A key passed to a log statement by mistake must not leak.
     */
    @Test
    void neverPrintsTheKey() {
        assertThat(new LlmApiKey(KEY).toString()).doesNotContain(KEY).doesNotContain("1234");
        assertThat(new UserLlmKeyRequest(KEY).toString()).doesNotContain(KEY);
    }

    @Test
    void carriesTheKeyAsABearerAuthorizationHeader() {
        assertThat(new LlmApiKey(KEY).authorizationHeaders())
                .containsExactly(Map.entry("Authorization", "Bearer " + KEY));
    }

    @Test
    void hintsWithTheLastFourCharacters() {
        assertThat(new LlmApiKey(KEY).hint()).isEqualTo("1234");
    }

    @Test
    void refusesABlankKey() {
        assertThatThrownBy(() -> new LlmApiKey(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmApiKey(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
