package com.solesonic.config.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserDirectoryConfigTest {

    private final UserDirectoryConfig config = new UserDirectoryConfig();

    @Test
    void buildsSettings() {
        UserDirectorySettings settings = config.userDirectorySettings(true, 20);

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.maxResults()).isEqualTo(20);
        assertThat(config.userDirectorySettings(false, 5).enabled()).isFalse();
    }

    @Test
    void rejectsANonPositiveMaxResults() {
        assertThatThrownBy(() -> config.userDirectorySettings(true, 0)).isInstanceOf(IllegalStateException.class);
    }
}
