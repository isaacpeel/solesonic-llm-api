package com.solesonic.config.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UserDirectoryConfig {

    @Bean
    public UserDirectorySettings userDirectorySettings(@Value("${solesonic.user-directory.enabled}") boolean enabled,
                                                       @Value("${solesonic.user-directory.max-results}") int maxResults) {
        if (maxResults < 1) {
            throw new IllegalStateException("USER_DIRECTORY_MAX_RESULTS must be at least 1");
        }

        return new UserDirectorySettings(enabled, maxResults);
    }
}
