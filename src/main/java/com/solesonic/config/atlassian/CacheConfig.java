package com.solesonic.config.atlassian;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {
    public static final String USER_TOKEN_CACHE = "userTokenCache";

    @Bean
    public Caffeine<Object, Object> caffeineConfig() {
        return Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES) // Cache entries expire after 10 minutes
                .maximumSize(1000);                     // Maximum 1000 entries
    }

    @Bean
    public CacheManager cacheManager(Caffeine<Object, Object> caffeine) {
        SimpleCacheManager cacheManager = new SimpleCacheManager();

        CaffeineCache myCache = new CaffeineCache(USER_TOKEN_CACHE, caffeine.build());

        cacheManager.setCaches(List.of(myCache));
        return cacheManager;
    }
}
