package com.solesonic.service.atlassian;

import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.service.user.UserPreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class AtlassianRefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(AtlassianRefreshTokenStore.class);

    private final UserPreferencesService userPreferencesService;

    public AtlassianRefreshTokenStore(UserPreferencesService userPreferencesService) {
        this.userPreferencesService = userPreferencesService;
    }

    public Optional<AtlassianAccessToken> loadRefreshToken(UUID userId) {
        UserPreferences userPreferences = userPreferencesService.get(userId);
        return Optional.of(userPreferences.getAtlassianAccessToken());
    }

    public void saveRefreshToken(UUID userId, AtlassianAccessToken atlassianAccessToken) {
        UserPreferences userPreferences = userPreferencesService.get(userId);
        userPreferences.setAtlassianAccessToken(atlassianAccessToken);

        userPreferencesService.update(userId, userPreferences);
    }
}