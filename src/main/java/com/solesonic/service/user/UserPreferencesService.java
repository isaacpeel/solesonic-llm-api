package com.solesonic.service.user;

import com.solesonic.model.user.UserPreferences;
import com.solesonic.repository.UserPreferencesRepository;
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

    @Value("${spring.ai.ollama.chat.model}")
    private String chatModel;

    @Value("${spring.ai.similarity-threshold}")
    private Double similarityThreshold;

    public UserPreferencesService(UserPreferencesRepository userPreferencesRepository) {
        this.userPreferencesRepository = userPreferencesRepository;
    }

    public UserPreferences get(UUID userId) {
        log.info("Getting user preferences for user ID: {}", userId);
        return userPreferencesRepository.findByUserId(userId)
                .orElseGet(() -> {
                    //Save new preferences if the current user doesn't have any.
                    UserPreferences userPreferences = new UserPreferences();
                    userPreferences.setModel(chatModel);
                    userPreferences.setSimilarityThreshold(similarityThreshold);
                    return save(userId, userPreferences);
                });
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
