package com.solesonic.service.atlassian;

import com.solesonic.config.atlassian.TokenBrokerProperties;
import com.solesonic.model.atlassian.auth.CacheKey;
import com.solesonic.model.atlassian.auth.CachedAccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AccessTokenCache {

    private static final Logger log = LoggerFactory.getLogger(AccessTokenCache.class);

    private final TokenBrokerProperties tokenBrokerProperties;
    private final Map<CacheKey, CachedAccessToken> cache = new ConcurrentHashMap<>();

    public AccessTokenCache(TokenBrokerProperties tokenBrokerProperties) {
        this.tokenBrokerProperties = tokenBrokerProperties;
    }



    public Optional<CachedAccessToken> get(UUID userId, String siteId) {
        if (!tokenBrokerProperties.getCache().isEnabled()) {
            log.debug("Cache is disabled, returning empty");
            return Optional.empty();
        }

        CacheKey key = new CacheKey(userId, siteId);
        CachedAccessToken cachedToken = cache.get(key);

        if (cachedToken == null) {
            log.debug("Cache miss for user {} siteId {}", userId, siteId);
            return Optional.empty();
        }

        // Check if token is expired with skew
        int skewSeconds = tokenBrokerProperties.getCache().getExpirySkewSeconds();
        if (cachedToken.isExpired(skewSeconds)) {
            log.debug("Cached token expired (with {} second skew) for user {} siteId {}", skewSeconds, userId, siteId);
            cache.remove(key); // Remove expired token
            return Optional.empty();
        }

        log.debug("Cache hit for user {} siteId {} - token valid for {} more seconds", 
                userId, siteId, 
                cachedToken.issuedAt().plusSeconds(cachedToken.expiresInSeconds()).toEpochSecond() - ZonedDateTime.now().toEpochSecond());
        return Optional.of(cachedToken);
    }

    public void put(UUID userId, String siteId, String accessToken, ZonedDateTime issuedAt, int expiresInSeconds) {
        if (!tokenBrokerProperties.getCache().isEnabled()) {
            log.debug("Cache is disabled, not storing token");
            return;
        }

        CacheKey key = new CacheKey(userId, siteId);
        CachedAccessToken cachedToken = new CachedAccessToken(accessToken, issuedAt, expiresInSeconds);
        
        cache.put(key, cachedToken);
        log.debug("Cached access token for user {} siteId {} (expires in {} seconds)", 
                userId, siteId, expiresInSeconds);
    }

    @SuppressWarnings("unused")
    public void evict(UUID userId, String siteId) {
        CacheKey key = new CacheKey(userId, siteId);
        CachedAccessToken removed = cache.remove(key);
        if (removed != null) {
            log.debug("Evicted cached token for user {} siteId {}", userId, siteId);
        }
    }

    @SuppressWarnings("unused")
    public void evictAll() {
        int size = cache.size();
        cache.clear();
        log.debug("Evicted all {} cached tokens", size);
    }

    public int size() {
        return cache.size();
    }

    @SuppressWarnings("unused")
    public void cleanupExpired() {
        if (!tokenBrokerProperties.getCache().isEnabled()) {
            return;
        }

        int skewSeconds = tokenBrokerProperties.getCache().getExpirySkewSeconds();
        int initialSize = cache.size();
        
        cache.entrySet().removeIf(entry -> {
            CachedAccessToken token = entry.getValue();
            if (token.isExpired(skewSeconds)) {
                log.debug("Removing expired token from cache for key {}", entry.getKey());
                return true;
            }
            return false;
        });
        
        int finalSize = cache.size();
        if (initialSize != finalSize) {
            log.debug("Cleanup removed {} expired tokens from cache ({} -> {})", 
                    initialSize - finalSize, initialSize, finalSize);
        }
    }
}