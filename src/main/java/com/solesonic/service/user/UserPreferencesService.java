package com.solesonic.service.user;

import com.solesonic.model.user.UserPreferences;
import com.solesonic.repository.UserPreferencesRepository;
import com.solesonic.service.atlassian.AtlassianTokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class UserPreferencesService {
    private static final Logger log = LoggerFactory.getLogger(UserPreferencesService.class);
    private final UserPreferencesRepository userPreferencesRepository;
    private final AtlassianTokenStore atlassianTokenStore;

    @Value("${spring.ai.ollama.chat.model}")
    private String chatModel;

    @Value("${spring.ai.similarity-threshold}")
    private Double similarityThreshold;

    public UserPreferencesService(UserPreferencesRepository userPreferencesRepository, AtlassianTokenStore atlassianTokenStore) {
        this.userPreferencesRepository = userPreferencesRepository;
        this.atlassianTokenStore = atlassianTokenStore;
    }

    public UserPreferences get(UUID userId) {
        log.debug("Getting user preferences for user ID: {}", userId);

        UserPreferences userPreferences = userPreferencesRepository.findByUserId(userId)
                .orElseGet(() -> {
                    //Save new preferences if the current user doesn't have any.
                    UserPreferences newPreferences = new UserPreferences();
                    newPreferences.setModel(chatModel);
                    newPreferences.setSimilarityThreshold(similarityThreshold);
                    return save(userId, newPreferences);
                });

        boolean hasAtlassianToken = atlassianTokenStore.exists(userId);

        userPreferences.setAtlassianAuthentication(hasAtlassianToken);
        
        return userPreferences;
    }

    public List<UserPreferences> findAll() {
        return userPreferencesRepository.findAll();
    }

    public UserPreferences save(UUID userId, UserPreferences userPreferences) {
        log.info("Saving user preferences");

        userPreferences.setUserId(userId);
        userPreferences.setCreated(ZonedDateTime.now());
        userPreferences.setUpdated(ZonedDateTime.now());

        return userPreferencesRepository.saveAndFlush(userPreferences);
    }

    public UserPreferences update(UUID userId, UserPreferences userPreferences) {
        log.info("Updating user preferences");
        userPreferences.setUserId(userId);
        userPreferences.setUpdated(ZonedDateTime.now());

        return userPreferencesRepository.save(userPreferences);
    }
}
