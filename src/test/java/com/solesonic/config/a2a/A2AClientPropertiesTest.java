package com.solesonic.config.a2a;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class A2AClientPropertiesTest {

    @Test
    void neverPrintsTheVirtualKey() {
        A2AClientProperties properties = new A2AClientProperties(false, 300, "http://agents",
                new A2AClientProperties.Litellm("http://litellm:4000", "sk-izzybot"));

        assertThat(properties.toString())
                .doesNotContain("sk-izzybot")
                .contains("http://litellm:4000");
    }
}
