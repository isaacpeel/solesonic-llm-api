package com.solesonic.config.atlassian;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "atlassian.token.broker")
public class TokenBrokerProperties {

    public static final String TOKEN_MINT_JIRA = "token:mint:jira";
    public static final String TOKEN_BROKER = "token-broker";

    private Cache cache = new Cache();
    private Retry retry = new Retry();
    private String requiredScope = TOKEN_MINT_JIRA;
    private String requiredAudience = TOKEN_BROKER;

    public static class Cache {
        private boolean enabled = true;
        private int expirySkewSeconds = 45;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getExpirySkewSeconds() {
            return expirySkewSeconds;
        }

        public void setExpirySkewSeconds(int expirySkewSeconds) {
            this.expirySkewSeconds = expirySkewSeconds;
        }
    }

    public static class Retry {
        private int maxAttempts = 3;
        private int baseDelayMs = 200;
        private boolean jitter = true;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getBaseDelayMs() {
            return baseDelayMs;
        }

        public void setBaseDelayMs(int baseDelayMs) {
            this.baseDelayMs = baseDelayMs;
        }

        public boolean isJitter() {
            return jitter;
        }

        public void setJitter(boolean jitter) {
            this.jitter = jitter;
        }
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public String getRequiredScope() {
        return requiredScope;
    }

    public void setRequiredScope(String requiredScope) {
        this.requiredScope = requiredScope;
    }

    public String getRequiredAudience() {
        return requiredAudience;
    }

    public void setRequiredAudience(String requiredAudience) {
        this.requiredAudience = requiredAudience;
    }
}