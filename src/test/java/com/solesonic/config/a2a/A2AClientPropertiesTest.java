package com.solesonic.config.a2a;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class A2AClientPropertiesTest {

    @Test
    void neverPrintsTheVirtualKey() {
        A2AClientProperties properties = new A2AClientProperties(false, 300, "http://agents",
                new A2AClientProperties.Litellm("http://litellm:4000", "sk-izzybot"),
                new A2AClientProperties.Sound(DataSize.ofMegabytes(5), Duration.ofDays(7)));

        assertThat(properties.toString())
                .doesNotContain("sk-izzybot")
                .contains("http://litellm:4000");
    }

    @Test
    void bindsTheSoundLimits() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "solesonic.a2a.base-uri", "http://agents",
                "solesonic.a2a.litellm.base-uri", "http://litellm:4000",
                "solesonic.a2a.litellm.api-key", "sk-izzybot",
                "solesonic.a2a.sound.max-bytes", "5MB",
                "solesonic.a2a.sound.ttl", "P7D"));

        A2AClientProperties properties = new Binder(source).bind("solesonic.a2a", A2AClientProperties.class).get();

        assertThat(properties.sound().maxBytes()).isEqualTo(DataSize.ofMegabytes(5));
        assertThat(properties.sound().ttl()).isEqualTo(Duration.ofDays(7));
    }
}
