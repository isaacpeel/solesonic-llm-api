package com.solesonic.model.atlassian.auth;

import java.util.UUID;

public record CacheKey(UUID userId, String siteId) {
}