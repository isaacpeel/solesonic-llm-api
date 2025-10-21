package com.solesonic.service.user;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import static com.solesonic.config.atlassian.CacheConfig.USER_TOKEN_CACHE;

@Service
public class UserTokenCacheService {
    public static final String USER_TOKEN_KEY = "user_token_key";

    private final CacheManager cacheManager;

    public UserTokenCacheService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void token(String key, String value) {
        Cache cache = cacheManager.getCache(USER_TOKEN_CACHE);

        if (cache != null) {
            cache.put(key, value);
        }
    }

    public String token(String key) {
        Cache cache = cacheManager.getCache(USER_TOKEN_CACHE);

        if (cache != null) {
            Cache.ValueWrapper wrapper = cache.get(key);

            if (wrapper != null) {
                return (String) wrapper.get();
            }
        }

        return null;
    }
}
